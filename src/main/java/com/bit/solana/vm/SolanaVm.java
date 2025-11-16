package com.bit.solana.vm;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

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

        // 执行合约方法（简化版：自动推断参数类型）
        public Object execute(String methodName, Object... args) throws Exception {
            // 自动获取参数类型数组（根据实际传入的args）
            Class<?>[] parameterTypes = new Class[args.length];
            for (int i = 0; i < args.length; i++) {
                parameterTypes[i] = args[i].getClass();
                // 处理基本类型包装类（如Integer -> int）
                if (parameterTypes[i] == Integer.class) parameterTypes[i] = int.class;
                if (parameterTypes[i] == Long.class) parameterTypes[i] = long.class;
                // 其他类型（如String、Boolean等）可按需补充
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
                if (parameterTypes[i] == Boolean.class) parameterTypes[i] = boolean.class;
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
