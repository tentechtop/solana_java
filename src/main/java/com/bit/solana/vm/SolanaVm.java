package com.bit.solana.vm;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.sql.Time;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static com.bit.solana.util.ClassUtil.decompressClassBytes;


/**
 * 区块链虚拟机实现，基于Java类加载器
 */
@Slf4j
public class SolanaVm {

    // Gas费用配置
    public static class GasConfig {
        // 基础操作Gas消耗
        public static final long BASE_GAS = 100;                  // 基础费用
        public static final long METHOD_INVOCATION_GAS = 500;     // 方法调用费用
        public static final long DATA_STORAGE_GAS_PER_BYTE = 10;  // 存储每字节费用
        public static final long DATA_TRANSFER_GAS_PER_BYTE = 5;  // 数据传输每字节费用
        public static final long SIGNATURE_VERIFICATION_GAS = 2000; // 签名验证费用
        public static final long BALANCE_UPDATE_GAS = 300;        // 余额更新费用

        // 方法特定Gas消耗映射
        private static final Map<String, Long> METHOD_SPECIFIC_GAS = new HashMap<>();

        static {
            // 初始化特定方法的额外Gas消耗
            METHOD_SPECIFIC_GAS.put("initAccount", 1000L);
            METHOD_SPECIFIC_GAS.put("transferWithSignature", 1500L);
            METHOD_SPECIFIC_GAS.put("signData", 800L);
            METHOD_SPECIFIC_GAS.put("verifySignature", 1200L);
        }

        // 获取方法的特定Gas消耗
        public static long getMethodSpecificGas(String methodName) {
            return METHOD_SPECIFIC_GAS.getOrDefault(methodName, 0L);
        }
    }

    // Gas计算结果
    @Data
    public static class GasResult {
        private final long gasUsed;       // 实际使用的Gas
        private final long gasPrice;      // Gas价格
        private final long totalCost;     // 总费用 (gasUsed * gasPrice)
        private final boolean success;    // 执行是否成功
        private final String error;       // 错误信息（如果失败）

        public GasResult(long gasUsed, long gasPrice, boolean success, String error) {
            this.gasUsed = gasUsed;
            this.gasPrice = gasPrice;
            this.totalCost = gasUsed * gasPrice;
            this.success = success;
            this.error = error;
        }
    }



    // 自定义类加载器，用于加载区块链中的class字节码
    public static class BlockchainClassLoader extends ClassLoader {
        // 禁止加载的敏感包
        private static final List<String> FORBIDDEN_CLASSES = Arrays.asList(
                // === 文件操作相关类 ===
                "java.io.File",                // 文件操作核心类
                "java.io.FileInputStream",     // 文件输入流
                "java.io.FileOutputStream",    // 文件输出流
                "java.io.FileReader",          // 文件字符输入流
                "java.io.FileWriter",          // 文件字符输出流
                "java.io.RandomAccessFile",    // 随机访问文件
                "java.nio.file.Files",         // NIO文件操作工具类
                "java.nio.file.Path",          // NIO路径类
                "java.nio.file.Paths",         // NIO路径工具类
                "java.nio.file.FileSystem",    // NIO文件系统
                "java.nio.file.FileSystems",   // NIO文件系统工具类

                // === 网络操作相关类 ===
                "java.net.Socket",             // TCP socket
                "java.net.ServerSocket",       // TCP服务器socket
                "java.net.DatagramSocket",     // UDP socket
                "java.net.DatagramPacket",     // UDP数据包
                "java.net.HttpURLConnection",  // HTTP连接
                "java.net.URL",                // URL处理（可能用于网络请求）
                "java.net.URLConnection",      // 通用URL连接
                "java.net.InetSocketAddress",  // 网络地址
                "java.net.SocketAddress",      //  socket地址
                "java.nio.channels.SocketChannel", // NIO socket通道
                "java.nio.channels.ServerSocketChannel" // NIO服务器socket通道
        );

        /**
         * 重写类加载逻辑：
         * 1. 允许加载任意路径的合约类（取消路径限制）
         * 2. 禁止加载java.io.相关的敏感类
         */
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            // 检查是否为禁止加载的敏感类（精确匹配或前缀匹配）
            for (String forbidden : FORBIDDEN_CLASSES) {
                // 精确匹配类名，或禁止其内部类（如java.io.File$InputStream）
                if (name.equals(forbidden) || name.startsWith(forbidden + "$")) {
                    throw new ClassNotFoundException("禁止加载敏感类: " + name);
                }
            }
            // 优先尝试从当前类加载器加载（如果是已加载过的合约类）
            Class<?> clazz = findLoadedClass(name);
            if (clazz != null) {
                if (resolve) {
                    resolveClass(clazz);
                }
                return clazz;
            }

            // 第三步：如果是系统类（如java.lang.、java.util.等非敏感类），委托给父类加载器
            // 避免覆盖系统类的加载逻辑（防止合约类伪装系统类）
            if (name.startsWith("java.") && !name.startsWith("java.io.")) {
                return super.loadClass(name, resolve);
            }

            // 第四步：对于非系统类（任意路径的合约类），尝试从字节码定义（如果已通过loadClassFromBytes加载）
            // 注意：这里需要配合defineClass的逻辑，确保合约类的字节码能被正确加载
            try {
                // 尝试从当前类加载器的字节码缓存中查找（如果有）
                // （如果你的逻辑中合约类是通过loadClassFromBytes加载的，这里会命中）
                return findClass(name);
            } catch (ClassNotFoundException e) {
                // 如果当前类加载器找不到，再委托给父类加载器（避免漏加载）
                return super.loadClass(name, resolve);
            }
        }

        // 重写findClass，确保合约类能被正确查找（如果需要自定义查找逻辑可在此实现）
        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            // 如果合约类是通过loadClassFromBytes加载的，defineClass会自动缓存，findLoadedClass会命中
            Class<?> clazz = findLoadedClass(name);
            if (clazz != null) {
                return clazz;
            }
            throw new ClassNotFoundException("Class not found: " + name);
        }


        // 从字节数组加载类
        public Class<?> loadClassFromBytes(byte[] classData) {
            return defineClass(null, classData, 0, classData.length);
        }

        // 新增：从压缩字节数组加载类（内部先解压）
        public Class<?> loadClassFromCompressedBytes(byte[] compressedClassData) throws IOException {
            byte[] originalData = decompressClassBytes(compressedClassData); // 解压
            return loadClassFromBytes(originalData); // 加载原始字节码
        }
    }


    // 合约执行器工具类，封装反射调用细节
    public static class ContractExecutor {
        private final Class<?> contractClass; // 预加载的合约类
        private final Object contractInstance; // 合约实例（复用，避免重复创建）

        // 构造器：从类对象初始化
        public ContractExecutor(Class<?> contractClass) throws InstantiationException, IllegalAccessException {
            this.contractClass = contractClass;
            this.contractInstance = contractClass.newInstance(); // 假设无参构造
        }

        // 执行合约方法
        public Object execute(String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
            Method method = contractClass.getMethod(methodName, paramTypes);
            return method.invoke(contractInstance, args);
        }

        public Object execute(String methodName, Object... args) throws Exception {
            // 自动获取参数类型数组（根据实际传入的args）
            Class<?>[] parameterTypes = new Class[args.length];
            for (int i = 0; i < args.length; i++) {
                parameterTypes[i] = args[i].getClass();
                if (parameterTypes[i] == Integer.class) parameterTypes[i] = int.class;
                if (parameterTypes[i] == Long.class) parameterTypes[i] = long.class;
                if (parameterTypes[i] == Float.class) parameterTypes[i] = float.class;
                if (parameterTypes[i] == Double.class) parameterTypes[i] = double.class;
                if (parameterTypes[i] == Boolean.class) parameterTypes[i] = boolean.class;
                if (parameterTypes[i] == Character.class) parameterTypes[i] = char.class;
                if (parameterTypes[i] == Byte.class) parameterTypes[i] = byte.class;
                if (parameterTypes[i] == Short.class) parameterTypes[i] = short.class;
                if (parameterTypes[i] == Void.class) parameterTypes[i] = void.class;
                if (parameterTypes[i] == String.class) parameterTypes[i] = String.class;
                if (parameterTypes[i] == Object.class) parameterTypes[i] = Object.class;
                if (parameterTypes[i] == Class.class) parameterTypes[i] = Class.class;
                if (parameterTypes[i] == Array.class) parameterTypes[i] = Array.class;
                if (parameterTypes[i] == Map.class) parameterTypes[i] = Map.class;
                if (parameterTypes[i] == List.class) parameterTypes[i] = List.class;
                if (parameterTypes[i] == Set.class) parameterTypes[i] = Set.class;
                if (parameterTypes[i] == Stack.class) parameterTypes[i] = Stack.class;
                if (parameterTypes[i] == Queue.class) parameterTypes[i] = Queue.class;
                if (parameterTypes[i] == Date.class) parameterTypes[i] = Date.class;
                if (parameterTypes[i] == Time.class) parameterTypes[i] = Time.class;
            }
            // 调用方法
            Method method = contractClass.getMethod(methodName, parameterTypes);
            return method.invoke(contractInstance, args);
        }
    }

    // 合约执行器工具类，封装反射调用和Gas计费
    public static class ContractExecutorGas {
        private final Class<?> contractClass;       // 预加载的合约类
        private final Object contractInstance;      // 合约实例
        private final long gasPrice;                // 当前Gas价格
        private long accumulatedGas;                // 累计消耗的Gas

        // 线程池：使用缓存线程池处理异步任务（可根据实际需求调整线程池参数）
        private static final ExecutorService ASYNC_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "contract-executor-async");
            thread.setDaemon(true); // 设为守护线程，避免影响程序退出
            return thread;
        });

        public CompletableFuture<Object[]> executeAsync(String methodName, Class<?>[] paramTypes, Object... args) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    // 使用显式paramTypes执行
                    return executeWithGasSync(methodName, paramTypes, args);
                } catch (Exception e) {
                    // 异常封装为GasResult
                    accumulatedGas = calculateFailedGas(methodName, args); // 计算失败时的Gas消耗
                    GasResult gasResult = new GasResult(
                            accumulatedGas,
                            gasPrice,
                            false,
                            "执行异常: " + e.getMessage()
                    );
                    return new Object[]{null, gasResult};
                }
            }, ASYNC_EXECUTOR);
        }

        public Object[] executeByName(String methodName, Object... args) throws Exception {
            accumulatedGas = 0;
            long startTime = System.nanoTime();

            try {
                // 基础Gas消耗（与原有逻辑一致）
                addGas(GasConfig.BASE_GAS + GasConfig.METHOD_INVOCATION_GAS);
                addGas(GasConfig.getMethodSpecificGas(methodName));
                for (Object arg : args) {
                    if (arg != null) addGas(calculateDataGas(arg));
                }

                // 关键：通过方法名唯一匹配方法（无需参数类型）
                Method method = findMethodByName(contractClass, methodName);
                if (method == null) {
                    throw new NoSuchMethodException("合约类中未找到方法: " + methodName);
                }

                // 执行方法（参数兼容性由调用方保证）
                Object result = method.invoke(contractInstance, args);

                // 剩余Gas计算逻辑（与原有逻辑一致）
                long executionTimeMicros = (System.nanoTime() - startTime) / 1000;
                addGas(executionTimeMicros);
                if ("transferWithSignature".equals(methodName)) {
                    addGas(GasConfig.SIGNATURE_VERIFICATION_GAS + GasConfig.BALANCE_UPDATE_GAS);
                } else if ("initAccount".equals(methodName)) {
                    addGas(GasConfig.BALANCE_UPDATE_GAS);
                }

                GasResult gasResult = new GasResult(accumulatedGas, gasPrice, true, null);
                return new Object[]{result, gasResult};
            } catch (Exception e) {
                long executionTimeMicros = (System.nanoTime() - startTime) / 1000;
                addGas(executionTimeMicros);
                GasResult gasResult = new GasResult(accumulatedGas, gasPrice, false, e.getMessage());
                return new Object[]{null, gasResult};
            }
        }


        /**
         * 异步执行（通过方法名匹配）并使用默认400ms超时
         * @param methodName 方法名
         * @param args 方法参数
         * @return 包含执行结果和Gas信息的CompletableFuture
         */
        public CompletableFuture<Object[]> executeByNameAsync(String methodName, Object... args) {
            return executeByNameAsyncWithTimeout(methodName, 400, args);
        }

        /**
         * 异步执行（通过方法名匹配）并指定超时时间
         * @param methodName 方法名
         * @param timeoutMillis 超时时间（毫秒）
         * @param args 方法参数
         * @return 包含执行结果和Gas信息的CompletableFuture
         */
        public CompletableFuture<Object[]> executeByNameAsyncWithTimeout(String methodName, long timeoutMillis, Object... args) {
            // 提交异步任务
            CompletableFuture<Object[]> asyncFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    // 调用同步执行方法
                    return executeByName(methodName, args);
                } catch (Exception e) {
                    // 执行异常时的Gas计算
                    long failedGas = calculateFailedGas(methodName, args);
                    GasResult gasResult = new GasResult(
                            failedGas,
                            gasPrice,
                            false,
                            "执行异常: " + e.getMessage()
                    );
                    return new Object[]{null, gasResult};
                }
            }, ASYNC_EXECUTOR);

            // 设置超时处理
            return asyncFuture.orTimeout(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        String errorMsg = ex instanceof java.util.concurrent.TimeoutException
                                ? "执行超时（" + timeoutMillis + "ms）"
                                : "异步任务异常: " + ex.getMessage();

                        // 超时场景下的基础Gas消耗
                        long timeoutGas = GasConfig.BASE_GAS + GasConfig.METHOD_INVOCATION_GAS
                                + GasConfig.getMethodSpecificGas(methodName);

                        GasResult gasResult = new GasResult(
                                timeoutGas,
                                gasPrice,
                                false,
                                errorMsg
                        );
                        return new Object[]{null, gasResult};
                    });
        }

        /**
         * 异步执行（通过方法名匹配）并注册回调（默认400ms超时）
         * @param methodName 方法名
         * @param onSuccess 成功回调
         * @param onFailure 失败回调
         * @param args 方法参数
         */
        public void executeByNameAsyncWithCallback(
                String methodName,
                Consumer<Object[]> onSuccess,
                Consumer<Throwable> onFailure,
                Object... args) {
            executeByNameAsync(methodName, args)
                    .thenAccept(onSuccess)
                    .exceptionally(ex -> {
                        onFailure.accept(ex);
                        return null;
                    });
        }

        /**
         * 异步执行合约方法并计算Gas消耗（非阻塞）
         * @param methodName 方法名
         * @param args 方法参数
         * @return CompletableFuture对象，用于获取执行结果或注册回调
         */
        public CompletableFuture<Object[]> executeAsync(String methodName, Object... args) {
            // 将执行逻辑提交到线程池，返回CompletableFuture
            return CompletableFuture.supplyAsync(() -> {
                try {
                    // 调用原有同步执行逻辑（复用Gas计算逻辑）
                    return executeWithGasSync(methodName, args);
                } catch (Exception e) {
                    // 异常封装为GasResult
                    accumulatedGas = calculateFailedGas(methodName, args); // 计算失败时的Gas消耗
                    GasResult gasResult = new GasResult(
                            accumulatedGas,
                            gasPrice,
                            false,
                            "执行异常: " + e.getMessage()
                    );
                    return new Object[]{null, gasResult};
                }
            }, ASYNC_EXECUTOR);
        }


        /**
         * 带超时的异步执行（默认400ms超时）
         * @param methodName 方法名
         * @param args 方法参数
         * @return 包含执行结果和Gas信息的CompletableFuture
         */
        public CompletableFuture<Object[]> executeAsyncWithTimeout(String methodName, Object... args) {
            return executeAsyncWithTimeout(methodName, 400, args); // 默认400ms超时
        }

        /**
         * 带自定义超时的异步执行
         * @param methodName 方法名
         * @param timeoutMillis 超时时间（毫秒）
         * @param args 方法参数
         * @return 包含执行结果和Gas信息的CompletableFuture
         */
        public CompletableFuture<Object[]> executeAsyncWithTimeout(String methodName, long timeoutMillis, Object... args) {
            // 提交异步任务
            CompletableFuture<Object[]> asyncFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return executeWithGasSync(methodName, args);
                } catch (Exception e) {
                    // 执行异常时的Gas计算
                    long failedGas = calculateFailedGas(methodName, args);
                    GasResult gasResult = new GasResult(
                            failedGas,
                            gasPrice,
                            false,
                            "执行异常: " + e.getMessage()
                    );
                    return new Object[]{null, gasResult};
                }
            }, ASYNC_EXECUTOR);

            // 设置超时处理：超时后返回超时异常的GasResult
            return asyncFuture.orTimeout(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        // 判断是否为超时异常
                        String errorMsg = ex instanceof java.util.concurrent.TimeoutException
                                ? "执行超时（" + timeoutMillis + "ms）"
                                : "异步任务异常: " + ex.getMessage();

                        // 超时场景下的基础Gas消耗（至少消耗基础Gas+方法调用Gas）
                        long timeoutGas = GasConfig.BASE_GAS + GasConfig.METHOD_INVOCATION_GAS
                                + GasConfig.getMethodSpecificGas(methodName);

                        GasResult gasResult = new GasResult(
                                timeoutGas,
                                gasPrice,
                                false,
                                errorMsg
                        );
                        return new Object[]{null, gasResult};
                    });
        }

        /**
         * 异步执行并注册回调
         * @param methodName 方法名
         * @param onSuccess 成功回调
         * @param onFailure 失败回调
         * @param args 方法参数
         */
        public void executeAsyncWithCallback(
                String methodName,
                Consumer<Object[]> onSuccess,
                Consumer<Throwable> onFailure,
                Object... args) {
            executeAsync(methodName, args)
                    .thenAccept(onSuccess)
                    .exceptionally(ex -> {
                        onFailure.accept(ex);
                        return null;
                    });
        }

        // 同步执行逻辑也需对应重载，使用显式paramTypes
        private Object[] executeWithGasSync(String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
            accumulatedGas = 0; // 重置累计Gas
            long startTime = System.nanoTime();

            try {
                // 基础Gas消耗
                addGas(GasConfig.BASE_GAS + GasConfig.METHOD_INVOCATION_GAS);

                // 方法特定Gas
                addGas(GasConfig.getMethodSpecificGas(methodName));

                // 参数数据Gas
                for (Object arg : args) {
                    if (arg != null) {
                        addGas(calculateDataGas(arg));
                    }
                }
                Method method = contractClass.getMethod(methodName, paramTypes); // 显式类型匹配
                Object result = method.invoke(contractInstance, args);
                // 计算执行时间产生的Gas (每微秒1单位Gas)
                long executionTimeMicros = (System.nanoTime() - startTime) / 1000;
                addGas(executionTimeMicros);

                // 特殊操作额外Gas
                if ("transferWithSignature".equals(methodName)) {
                    addGas(GasConfig.SIGNATURE_VERIFICATION_GAS + GasConfig.BALANCE_UPDATE_GAS);
                } else if ("initAccount".equals(methodName)) {
                    addGas(GasConfig.BALANCE_UPDATE_GAS);
                }

                GasResult gasResult = new GasResult(accumulatedGas, gasPrice, true, null);
                return new Object[]{result, gasResult};
            } catch (Exception e) {
                // 执行失败时计算已消耗的Gas
                long executionTimeMicros = (System.nanoTime() - startTime) / 1000;
                addGas(executionTimeMicros);
                throw e; // 抛出异常由外层异步逻辑处理
            }

        }

        /**
         * 同步执行逻辑（原有核心逻辑，作为异步任务的内部实现）
         */
        private Object[] executeWithGasSync(String methodName, Object... args) throws Exception {
            accumulatedGas = 0; // 重置累计Gas
            long startTime = System.nanoTime();

            try {
                // 基础Gas消耗
                addGas(GasConfig.BASE_GAS + GasConfig.METHOD_INVOCATION_GAS);

                // 方法特定Gas
                addGas(GasConfig.getMethodSpecificGas(methodName));

                // 参数数据Gas
                for (Object arg : args) {
                    if (arg != null) {
                        addGas(calculateDataGas(arg));
                    }
                }

                // 执行方法
                Class<?>[] parameterTypes = getParameterTypes(args);
                Method method = contractClass.getMethod(methodName, parameterTypes);
                Object result = method.invoke(contractInstance, args);

                // 计算执行时间产生的Gas (每微秒1单位Gas)
                long executionTimeMicros = (System.nanoTime() - startTime) / 1000;
                addGas(executionTimeMicros);

                // 特殊操作额外Gas
                if ("transferWithSignature".equals(methodName)) {
                    addGas(GasConfig.SIGNATURE_VERIFICATION_GAS + GasConfig.BALANCE_UPDATE_GAS);
                } else if ("initAccount".equals(methodName)) {
                    addGas(GasConfig.BALANCE_UPDATE_GAS);
                }

                GasResult gasResult = new GasResult(accumulatedGas, gasPrice, true, null);
                return new Object[]{result, gasResult};
            } catch (Exception e) {
                // 执行失败时计算已消耗的Gas
                long executionTimeMicros = (System.nanoTime() - startTime) / 1000;
                addGas(executionTimeMicros);
                throw e; // 抛出异常由外层异步逻辑处理
            }
        }

        /**
         * 计算执行失败时的Gas消耗（基础消耗+已执行步骤的消耗）
         */
        private long calculateFailedGas(String methodName, Object... args) {
            long failedGas = GasConfig.BASE_GAS; // 至少消耗基础Gas
            failedGas += GasConfig.getMethodSpecificGas(methodName);
            for (Object arg : args) {
                if (arg != null) {
                    failedGas += calculateDataGas(arg);
                }
            }
            return failedGas;
        }


        // 构造器：从类对象初始化，默认Gas价格
        public ContractExecutorGas(Class<?> contractClass) throws InstantiationException, IllegalAccessException {
            this(contractClass, 100L); // 默认100单位/ Gas
        }

        // 构造器：指定Gas价格
        public ContractExecutorGas(Class<?> contractClass, long gasPrice) throws InstantiationException, IllegalAccessException {
            this.contractClass = contractClass;
            this.contractInstance = contractClass.newInstance(); // 假设无参构造
            this.gasPrice = gasPrice;
            this.accumulatedGas = 0;
        }

        // 新增：接收已初始化的合约实例（已注入数据库等依赖）
        public ContractExecutorGas(Object contractInstance) {
            this(contractInstance, 100L); // 默认Gas价格
        }

        // 新增：接收合约实例和自定义Gas价格
        public ContractExecutorGas(Object contractInstance, long gasPrice) {
            this.contractInstance = contractInstance;
            this.contractClass = contractInstance.getClass(); // 从实例获取类对象
            this.gasPrice = gasPrice;
            this.accumulatedGas = 0;
        }

        /**
         * 预估方法执行所需的Gas
         * @param methodName 方法名
         * @param args 方法参数
         * @return 预估的Gas数量
         */
        public long estimateGas(String methodName, Object... args) {
            long estimatedGas = GasConfig.BASE_GAS + GasConfig.METHOD_INVOCATION_GAS;

            // 方法特定Gas
            estimatedGas += GasConfig.getMethodSpecificGas(methodName);

            // 计算参数数据大小产生的Gas
            for (Object arg : args) {
                if (arg != null) {
                    estimatedGas += calculateDataGas(arg);
                }
            }

            // 特殊方法额外Gas
            switch (methodName) {
                case "transferWithSignature":
                    estimatedGas += GasConfig.SIGNATURE_VERIFICATION_GAS + GasConfig.BALANCE_UPDATE_GAS;
                    break;
                case "initAccount":
                    estimatedGas += GasConfig.BALANCE_UPDATE_GAS;
                    break;
            }

            return estimatedGas;
        }

        /**
         * 执行合约方法并计算Gas消耗
         * @param methodName 方法名
         * @param args 方法参数
         * @return 包含执行结果和Gas消耗的对象数组 [result, gasResult]
         */
        public Object[] executeWithGas(String methodName, Object... args) throws Exception {
            // 重置累计Gas
            accumulatedGas = 0;

            // 记录开始时间用于计算执行时间Gas
            long startTime = System.nanoTime();

            try {
                // 基础Gas消耗
                addGas(GasConfig.BASE_GAS + GasConfig.METHOD_INVOCATION_GAS);

                // 方法特定Gas
                addGas(GasConfig.getMethodSpecificGas(methodName));

                // 参数数据Gas
                for (Object arg : args) {
                    if (arg != null) {
                        addGas(calculateDataGas(arg));
                    }
                }

                // 执行方法
                Class<?>[] parameterTypes = getParameterTypes(args);
                Method method = contractClass.getMethod(methodName, parameterTypes);
                Object result = method.invoke(contractInstance, args);

                // 计算执行时间产生的Gas (每微秒1单位Gas)
                long executionTimeMicros = (System.nanoTime() - startTime) / 1000;
                addGas(executionTimeMicros);

                // 特殊操作额外Gas
                if ("transferWithSignature".equals(methodName)) {
                    addGas(GasConfig.SIGNATURE_VERIFICATION_GAS + GasConfig.BALANCE_UPDATE_GAS);
                } else if ("initAccount".equals(methodName)) {
                    addGas(GasConfig.BALANCE_UPDATE_GAS);
                }

                GasResult gasResult = new GasResult(accumulatedGas, gasPrice, true, null);
                return new Object[]{result, gasResult};
            } catch (Exception e) {
                // 即使执行失败也会消耗部分Gas
                long executionTimeMicros = (System.nanoTime() - startTime) / 1000;
                addGas(executionTimeMicros);

                GasResult gasResult = new GasResult(accumulatedGas, gasPrice, false, e.getMessage());
                return new Object[]{null, gasResult};
            }
        }

        // 新增：支持手动指定参数类型的重载方法
        public Object[] executeWithGas(String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
            accumulatedGas = 0;
            long startTime = System.nanoTime();

            try {
                // 基础Gas消耗（与原逻辑一致）
                addGas(GasConfig.BASE_GAS + GasConfig.METHOD_INVOCATION_GAS);
                addGas(GasConfig.getMethodSpecificGas(methodName));
                for (Object arg : args) {
                    if (arg != null) addGas(calculateDataGas(arg));
                }

                // 关键：使用手动指定的paramTypes查找方法
                Method method = contractClass.getMethod(methodName, paramTypes);
                Object result = method.invoke(contractInstance, args);

                // 剩余Gas计算逻辑（与原逻辑一致）
                long executionTimeMicros = (System.nanoTime() - startTime) / 1000;
                addGas(executionTimeMicros);
                if ("transferWithSignature".equals(methodName)) {
                    addGas(GasConfig.SIGNATURE_VERIFICATION_GAS + GasConfig.BALANCE_UPDATE_GAS);
                } else if ("initAccount".equals(methodName)) {
                    addGas(GasConfig.BALANCE_UPDATE_GAS);
                }

                GasResult gasResult = new GasResult(accumulatedGas, gasPrice, true, null);
                return new Object[]{result, gasResult};
            } catch (Exception e) {
                long executionTimeMicros = (System.nanoTime() - startTime) / 1000;
                addGas(executionTimeMicros);
                GasResult gasResult = new GasResult(accumulatedGas, gasPrice, false, e.getMessage());
                return new Object[]{null, gasResult};
            }
        }

        /**
         * 辅助方法：通过方法名在类中查找唯一方法（假设无重载）
         */
        public Method findMethodByName(Class<?> clazz, String methodName) {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    return method;
                }
            }
            // 检查父类（如果需要）
            if (clazz.getSuperclass() != null) {
                return findMethodByName(clazz.getSuperclass(), methodName);
            }
            return null;
        }

        /**
         * 计算数据占用产生的Gas
         * @param data 数据对象
         * @return Gas数量
         */
        private long calculateDataGas(Object data) {
            if (data instanceof byte[]) {
                return ((byte[]) data).length * GasConfig.DATA_TRANSFER_GAS_PER_BYTE;
            } else if (data instanceof String) {
                return ((String) data).getBytes().length * GasConfig.DATA_TRANSFER_GAS_PER_BYTE;
            } else if (data instanceof Number) {
                return 8 * GasConfig.DATA_TRANSFER_GAS_PER_BYTE; // 数字类型按8字节算
            } else {
                // 其他对象按序列化估算
                return 32 * GasConfig.DATA_TRANSFER_GAS_PER_BYTE; // 默认32字节估算
            }
        }

        /**
         * 获取参数类型数组
         */
        private Class<?>[] getParameterTypes(Object... args) {
            Class<?>[] parameterTypes = new Class[args.length];
            for (int i = 0; i < args.length; i++) {
                parameterTypes[i] = args[i].getClass();
                // 处理基本类型包装类
                if (parameterTypes[i] == Integer.class) parameterTypes[i] = int.class;
                if (parameterTypes[i] == Long.class) parameterTypes[i] = long.class;
                if (parameterTypes[i] == Float.class) parameterTypes[i] = float.class;
                if (parameterTypes[i] == Double.class) parameterTypes[i] = double.class;
                if (parameterTypes[i] == Boolean.class) parameterTypes[i] = boolean.class;
                if (parameterTypes[i] == Character.class) parameterTypes[i] = char.class;
                if (parameterTypes[i] == Byte.class) parameterTypes[i] = byte.class;
                if (parameterTypes[i] == Short.class) parameterTypes[i] = short.class;
                if (parameterTypes[i] == Void.class) parameterTypes[i] = void.class;
                if (parameterTypes[i] == String.class) parameterTypes[i] = String.class;
                if (parameterTypes[i] == Object.class) parameterTypes[i] = Object.class;
                if (parameterTypes[i] == Class.class) parameterTypes[i] = Class.class;
                if (parameterTypes[i] == Array.class) parameterTypes[i] = Array.class;
                if (parameterTypes[i] == Map.class) parameterTypes[i] = Map.class;
                if (parameterTypes[i] == List.class) parameterTypes[i] = List.class;
                if (parameterTypes[i] == Set.class) parameterTypes[i] = Set.class;
                if (parameterTypes[i] == Stack.class) parameterTypes[i] = Stack.class;
                if (parameterTypes[i] == Queue.class) parameterTypes[i] = Queue.class;
                if (parameterTypes[i] == Date.class) parameterTypes[i] = Date.class;
                if (parameterTypes[i] == Time.class) parameterTypes[i] = Time.class;
            }
            return parameterTypes;
        }

        /**
         * 累加Gas消耗
         */
        private void addGas(long amount) {
            if (amount < 0) return;
            accumulatedGas += amount;
        }

        /**
         * 获取累计消耗的Gas
         */
        public long getAccumulatedGas() {
            return accumulatedGas;
        }
    }



}
