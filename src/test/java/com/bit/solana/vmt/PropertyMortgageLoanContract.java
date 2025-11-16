package com.bit.solana.vmt;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;

public class PropertyMortgageLoanContract {
    // 核心存储结构（扩展原有基础，新增借贷/质押/估值相关存储）
    private static final Map<String, KeyPair> accountKeys = new ConcurrentHashMap<>();
    private static final Map<String, Long> tokenBalances = new ConcurrentHashMap<>(); // 代币余额
    private static final Map<String, Long> fundBalances = new ConcurrentHashMap<>();  // 资金余额
    private static final Map<String, Long> stakedTokens = new ConcurrentHashMap<>();  // 质押代币余额
    private static final List<Map<String, Object>> loans = new ArrayList<>();         // 借贷订单
    private static final List<Map<String, Object>> transactions = new ArrayList<>();  // 全量交易记录
    private static final Set<String> processedTxIds = new HashSet<>();
    private static final Map<String, Object> propertyInfo = new ConcurrentHashMap<>(); // 房产+合约核心参数
    private static final Map<String, Object> marketInfo = new ConcurrentHashMap<>();   // 市场动态（估值/利率）

    // 系统参数（细化风险控制和业务规则）
    private static final String CURVE_NAME = "secp256r1";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static final long TOTAL_TOKEN_SUPPLY = 100000000L; // 1亿份RWA代币
    private static final String TOKEN_NAME = "PropertyBackedToken";
    private static final String TOKEN_SYMBOL = "PBT";
    private static long transactionCounter = 0;
    private static final String PROPERTY_OWNER = "PropertyAdmin"; // 资产管理员
    private static final String LENDER_ROLE = "LenderPool";       // 资金池账户（提供借贷资金）
    private static final double MAX_LOAN_RATIO = 0.7;             // 最高抵押率（70%）
    private static final double LIQUIDATION_RATIO = 0.6;          // 清算触发率（60%）
    private static final double DAILY_INTEREST_RATE = 0.0005;     // 日利率（0.05%）
    private static final long INTEREST_SETTLE_INTERVAL = 86400000L; // 利息结算周期（24小时）

    // 初始化（扩展原有逻辑，新增借贷/市场参数）
    static {
        // 房产基础信息
        propertyInfo.put("name", "城市中心商业大厦");
        propertyInfo.put("location", "市中心金融区88号");
        propertyInfo.put("initialValuation", 500000000L); // 初始估值5亿元
        propertyInfo.put("currentValuation", 500000000L); // 当前估值（可动态更新）
        propertyInfo.put("totalTokens", TOTAL_TOKEN_SUPPLY);
        propertyInfo.put("remainingTokens", TOTAL_TOKEN_SUPPLY);
        propertyInfo.put("tokenPrice", 5L); // 初始代币价格（=初始估值/总代币）
        propertyInfo.put("status", "ACTIVE");
        propertyInfo.put("launchTime", System.currentTimeMillis());

        // 市场动态参数（利率、估值更新时间）
        marketInfo.put("dailyInterestRate", DAILY_INTEREST_RATE);
        marketInfo.put("lastValuationUpdateTime", System.currentTimeMillis());
        marketInfo.put("lastInterestSettleTime", System.currentTimeMillis());
    }

    // 1. 账户创建（扩展角色：管理员、资金池、普通用户）
    public static String createAccount(String address, String role) {
        if (address == null || address.trim().isEmpty() || role == null) {
            throw new IllegalArgumentException("地址和角色不能为空");
        }
        if (!Arrays.asList("ADMIN", "LENDER", "USER").contains(role)) {
            throw new IllegalArgumentException("无效角色：仅支持ADMIN/LENDER/USER");
        }
        if (accountKeys.containsKey(address)) {
            throw new IllegalStateException("账户已存在: " + address);
        }

        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
            ECGenParameterSpec ecSpec = new ECGenParameterSpec(CURVE_NAME);
            keyGen.initialize(ecSpec, new SecureRandom());
            KeyPair keyPair = keyGen.generateKeyPair();

            accountKeys.put(address, keyPair);
            tokenBalances.putIfAbsent(address, 0L);
            fundBalances.putIfAbsent(address, 0L);
            stakedTokens.putIfAbsent(address, 0L);

            // 初始化特殊角色资产
            if ("ADMIN".equals(role) && PROPERTY_OWNER.equals(address)) {
                tokenBalances.put(address, TOTAL_TOKEN_SUPPLY); // 管理员持有全部代币
            }
            if ("LENDER".equals(role) && LENDER_ROLE.equals(address)) {
                fundBalances.put(address, 100000000L); // 资金池初始注入1亿元借贷资金
            }

            return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        } catch (Exception e) {
            throw new RuntimeException("账户创建失败", e);
        }
    }

    // 2. 代币交易（新增：用户间代币买卖，而非仅向管理员购买）
    public static Map<String, Object> tradeTokens(Map<String, Object> transaction, String signature) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "FAILED");

        // 提取交易字段（卖方、买方、数量、单价、交易ID、时间戳）
        String seller = (String) transaction.get("seller");
        String buyer = (String) transaction.get("buyer");
        Long tokenAmount = (Long) transaction.get("tokenAmount");
        Long unitPrice = (Long) transaction.get("unitPrice");
        String txId = (String) transaction.get("txId");
        Long timestamp = (Long) transaction.get("timestamp");

        // 基础校验
        if (seller == null || buyer == null || tokenAmount == null || unitPrice == null || txId == null || timestamp == null) {
            result.put("message", "交易数据不完整");
            return result;
        }
        if (!accountKeys.containsKey(seller) || !accountKeys.containsKey(buyer)) {
            result.put("message", "买卖双方账户不存在");
            return result;
        }
        if (tokenAmount <= 0 || unitPrice <= 0) {
            result.put("message", "数量/单价必须大于0");
            return result;
        }
        if (processedTxIds.contains(txId)) {
            result.put("message", "交易已处理（防重放）");
            return result;
        }

        try {
            long totalFund = tokenAmount * unitPrice;

            // 校验卖方代币余额、买方资金余额
            if (getTokenBalance(seller) < tokenAmount) {
                throw new IllegalStateException("卖方代币不足，当前: " + getTokenBalance(seller));
            }
            if (getFundBalance(buyer) < totalFund) {
                throw new IllegalStateException("买方资金不足，当前: " + getFundBalance(buyer));
            }

            // 签名验证（严格使用交易中的timestamp）
            String sellerPubKey = Base64.getEncoder().encodeToString(accountKeys.get(seller).getPublic().getEncoded());
            if (!verifyTransaction(sellerPubKey, transaction, signature)) {
                throw new SecurityException("卖方签名验证失败");
            }

            // 执行交易（同步锁保证一致性）
            synchronized (PropertyMortgageLoanContract.class) {
                tokenBalances.put(seller, getTokenBalance(seller) - tokenAmount);
                tokenBalances.put(buyer, getTokenBalance(buyer) + tokenAmount);
                fundBalances.put(buyer, getFundBalance(buyer) - totalFund);
                fundBalances.put(seller, getFundBalance(seller) + totalFund);

                // 记录交易
                Map<String, Object> txRecord = new HashMap<>(transaction);
                txRecord.put("type", "TOKEN_TRADE");
                txRecord.put("totalFund", totalFund);
                txRecord.put("status", "SUCCESS");
                transactions.add(txRecord);
                processedTxIds.add(txId);
            }

            result.put("status", "SUCCESS");
            result.put("message", "代币交易成功");
            result.put("totalFund", totalFund);
            result.put("sellerTokenBalance", getTokenBalance(seller));
            result.put("buyerTokenBalance", getTokenBalance(buyer));
        } catch (Exception e) {
            result.put("message", e.getMessage());
        }
        return result;
    }

    // 3. 质押代币借款（核心新增功能）
    public static Map<String, Object> mortgageBorrow(Map<String, Object> transaction, String signature) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "FAILED");

        // 提取交易字段（借款人、质押代币量、借款金额、交易ID、时间戳）
        String borrower = (String) transaction.get("borrower");
        Long stakeTokenAmount = (Long) transaction.get("stakeTokenAmount");
        Long borrowFundAmount = (Long) transaction.get("borrowFundAmount");
        String txId = (String) transaction.get("txId");
        Long timestamp = (Long) transaction.get("timestamp");

        // 基础校验
        if (borrower == null || stakeTokenAmount == null || borrowFundAmount == null || txId == null || timestamp == null) {
            result.put("message", "借款数据不完整");
            return result;
        }
        if (!accountKeys.containsKey(borrower) || !accountKeys.containsKey(LENDER_ROLE)) {
            result.put("message", "账户不存在（借款人/资金池）");
            return result;
        }
        if (stakeTokenAmount <= 0 || borrowFundAmount <= 0) {
            result.put("message", "质押量/借款金额必须大于0");
            return result;
        }
        if (processedTxIds.contains(txId)) {
            result.put("message", "借款申请已处理");
            return result;
        }

        try {
            // 核心风险校验：抵押率不超过70%
            long currentTokenPrice = (long) propertyInfo.get("tokenPrice");
            long stakeValue = stakeTokenAmount * currentTokenPrice; // 质押物总价值
            double loanRatio = (double) borrowFundAmount / stakeValue; // 实际抵押率

            if (loanRatio > MAX_LOAN_RATIO) {
                throw new IllegalStateException("抵押率超标（当前" + loanRatio + "，最高" + MAX_LOAN_RATIO + "）");
            }
            if (getTokenBalance(borrower) < stakeTokenAmount) {
                throw new IllegalStateException("质押代币不足，当前: " + getTokenBalance(borrower));
            }
            if (getFundBalance(LENDER_ROLE) < borrowFundAmount) {
                throw new IllegalStateException("资金池资金不足，当前: " + getFundBalance(LENDER_ROLE));
            }

            // 签名验证
            String borrowerPubKey = Base64.getEncoder().encodeToString(accountKeys.get(borrower).getPublic().getEncoded());
            if (!verifyTransaction(borrowerPubKey, transaction, signature)) {
                throw new SecurityException("借款人签名验证失败");
            }

            // 创建借贷订单（记录关键信息，用于计息/清算）
            String loanId = generateLoanId(borrower, timestamp);

            // 执行质押+放款（同步锁）
            synchronized (PropertyMortgageLoanContract.class) {
                // 锁定质押代币
                tokenBalances.put(borrower, getTokenBalance(borrower) - stakeTokenAmount);
                stakedTokens.put(borrower, getStakedBalance(borrower) + stakeTokenAmount);

                // 资金池放款
                fundBalances.put(LENDER_ROLE, getFundBalance(LENDER_ROLE) - borrowFundAmount);
                fundBalances.put(borrower, getFundBalance(borrower) + borrowFundAmount);


                Map<String, Object> loanOrder = new HashMap<>();
                loanOrder.put("loanId", loanId);
                loanOrder.put("borrower", borrower);
                loanOrder.put("stakeTokenAmount", stakeTokenAmount);
                loanOrder.put("borrowFundAmount", borrowFundAmount);
                loanOrder.put("stakeValue", stakeValue);
                loanOrder.put("loanRatio", loanRatio);
                loanOrder.put("dailyInterestRate", DAILY_INTEREST_RATE);
                loanOrder.put("borrowTime", timestamp);
                loanOrder.put("lastInterestSettleTime", timestamp);
                loanOrder.put("unpaidInterest", 0L);
                loanOrder.put("status", "ACTIVE"); // ACTIVE/CLEARED/LIQUIDATED
                loans.add(loanOrder);

                // 记录交易
                Map<String, Object> txRecord = new HashMap<>(transaction);
                txRecord.put("type", "MORTGAGE_BORROW");
                txRecord.put("loanId", loanId);
                txRecord.put("loanRatio", loanRatio);
                txRecord.put("status", "SUCCESS");
                transactions.add(txRecord);
                processedTxIds.add(txId);
            }

            result.put("status", "SUCCESS");
            result.put("message", "质押借款成功");
            result.put("loanId", loanId);
            result.put("stakedTokenAmount", stakeTokenAmount);
            result.put("availableFund", getFundBalance(borrower));
        } catch (Exception e) {
            result.put("message", e.getMessage());
        }
        return result;
    }

    // 4. 偿还借款+解除质押（核心新增功能）
    public static Map<String, Object> repayLoan(Map<String, Object> transaction, String signature) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "FAILED");

        // 提取交易字段（借款人、贷款ID、还款金额、交易ID、时间戳）
        String borrower = (String) transaction.get("borrower");
        String loanId = (String) transaction.get("loanId");
        Long repayAmount = (Long) transaction.get("repayAmount");
        String txId = (String) transaction.get("txId");
        Long timestamp = (Long) transaction.get("timestamp");

        // 基础校验
        if (borrower == null || loanId == null || repayAmount == null || txId == null || timestamp == null) {
            result.put("message", "还款数据不完整");
            return result;
        }
        if (!accountKeys.containsKey(borrower)) {
            result.put("message", "借款人账户不存在");
            return result;
        }
        if (repayAmount <= 0) {
            result.put("message", "还款金额必须大于0");
            return result;
        }
        if (processedTxIds.contains(txId)) {
            result.put("message", "还款交易已处理");
            return result;
        }

        try {
            // 查找未结清的贷款订单
            Map<String, Object> loanOrder = findActiveLoan(loanId, borrower);
            if (loanOrder == null) {
                throw new IllegalStateException("未找到有效贷款订单");
            }

            // 计算应付利息（按日计息，从上次结算到当前时间）
            long borrowFund = (long) loanOrder.get("borrowFundAmount");
            long unpaidInterest = (long) loanOrder.get("unpaidInterest");
            long lastSettleTime = (long) loanOrder.get("lastInterestSettleTime");
            long days = (timestamp - lastSettleTime) / INTEREST_SETTLE_INTERVAL;
            long interest = (long) (borrowFund * DAILY_INTEREST_RATE * days);
            long totalOwe = borrowFund + unpaidInterest + interest; // 本息合计

            // 校验还款金额
            if (repayAmount < totalOwe) {
                throw new IllegalStateException("还款金额不足，应付本息: " + totalOwe);
            }
            if (getFundBalance(borrower) < repayAmount) {
                throw new IllegalStateException("借款人资金不足，当前                资金: " + getFundBalance(borrower));
            }

            // 签名验证
            String borrowerPubKey = Base64.getEncoder().encodeToString(accountKeys.get(borrower).getPublic().getEncoded());
            if (!verifyTransaction(borrowerPubKey, transaction, signature)) {
                throw new SecurityException("借款人签名验证失败");
            }

            // 执行还款+解押（同步锁）
            synchronized (PropertyMortgageLoanContract.class) {
                // 扣减借款人资金（包含本金+利息）
                fundBalances.put(borrower, getFundBalance(borrower) - repayAmount);
                // 资金池接收还款（本金+利息）
                fundBalances.put(LENDER_ROLE, getFundBalance(LENDER_ROLE) + totalOwe);
                // 多余还款退回借款人
                if (repayAmount > totalOwe) {
                    fundBalances.put(borrower, getFundBalance(borrower) + (repayAmount - totalOwe));
                }

                // 解除质押代币
                long stakeAmount = (long) loanOrder.get("stakeTokenAmount");
                stakedTokens.put(borrower, getStakedBalance(borrower) - stakeAmount);
                tokenBalances.put(borrower, getTokenBalance(borrower) + stakeAmount);

                // 更新贷款状态
                loanOrder.put("status", "CLEARED");
                loanOrder.put("repayTime", timestamp);
                loanOrder.put("totalRepaid", totalOwe);
                loanOrder.put("unpaidInterest", 0L);

                // 记录交易
                Map<String, Object> txRecord = new HashMap<>(transaction);
                txRecord.put("type", "LOAN_REPAY");
                txRecord.put("totalOwe", totalOwe);
                txRecord.put("interest", interest);
                txRecord.put("status", "SUCCESS");
                transactions.add(txRecord);
                processedTxIds.add(txId);
            }

            result.put("status", "SUCCESS");
            result.put("message", "还款成功，质押代币已解除锁定");
            result.put("totalOwe", totalOwe);
            result.put("interest", interest);
            result.put("remainingFund", getFundBalance(borrower));
            result.put("tokenBalance", getTokenBalance(borrower));
        } catch (Exception e) {
            result.put("message", e.getMessage());
        }
        return result;
    }

    // 5. 自动清算（当抵押率低于清算线时触发）
    public static Map<String, Object> liquidateLoans(Map<String, Object> transaction, String signature) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "FAILED");

        // 提取交易字段（管理员地址、交易ID、时间戳）
        String admin = (String) transaction.get("admin");
        String txId = (String) transaction.get("txId");
        Long timestamp = (Long) transaction.get("timestamp");

        // 基础校验
        if (admin == null || txId == null || timestamp == null) {
            result.put("message", "清算数据不完整");
            return result;
        }
        if (!PROPERTY_OWNER.equals(admin) || !accountKeys.containsKey(admin)) {
            result.put("message", "无权限执行清算（需管理员身份）");
            return result;
        }
        if (processedTxIds.contains(txId)) {
            result.put("message", "清算交易已处理");
            return result;
        }

        try {
            // 验证管理员签名
            String adminPubKey = Base64.getEncoder().encodeToString(accountKeys.get(admin).getPublic().getEncoded());
            if (!verifyTransaction(adminPubKey, transaction, signature)) {
                throw new SecurityException("管理员签名验证失败");
            }

            // 检查房产估值是否更新（更新代币价格）
            updateTokenPrice();

            List<String> liquidatedLoans = new ArrayList<>();
            synchronized (PropertyMortgageLoanContract.class) {
                // 遍历所有活跃贷款，检查抵押率是否低于清算线
                for (Map<String, Object> loan : loans) {
                    if (!"ACTIVE".equals(loan.get("status"))) continue;

                    String loanId = (String) loan.get("loanId");
                    String borrower = (String) loan.get("borrower");
                    long stakeTokenAmount = (long) loan.get("stakeTokenAmount");
                    long borrowFund = (long) loan.get("borrowFundAmount");
                    long currentTokenPrice = (long) propertyInfo.get("tokenPrice");
                    long currentStakeValue = stakeTokenAmount * currentTokenPrice; // 当前质押物价值
                    double currentLoanRatio = (double) borrowFund / currentStakeValue;

                    // 抵押率高于清算线时清算
                    if (currentLoanRatio > LIQUIDATION_RATIO ){
                        // 计算未还利息
                        long unpaidInterest = (long) loan.get("unpaidInterest");
                        long lastSettleTime = (long) loan.get("lastInterestSettleTime");
                        long days = (timestamp - lastSettleTime) / INTEREST_SETTLE_INTERVAL;
                        long interest = (long) (borrowFund * DAILY_INTEREST_RATE * days);
                        long totalOwe = borrowFund + unpaidInterest + interest;

                        // 质押代币归资金池所有（用于偿还欠款）
                        stakedTokens.put(borrower, getStakedBalance(borrower) - stakeTokenAmount);
                        tokenBalances.put(LENDER_ROLE, getTokenBalance(LENDER_ROLE) + stakeTokenAmount);

                        // 更新贷款状态
                        loan.put("status", "LIQUIDATED");
                        loan.put("liquidateTime", timestamp);
                        loan.put("totalOwe", totalOwe);
                        loan.put("liquidateRatio", currentLoanRatio);

                        liquidatedLoans.add(loanId);
                    }
                }

                // 记录清算交易
                Map<String, Object> txRecord = new HashMap<>(transaction);
                txRecord.put("type", "LOAN_LIQUIDATION");
                txRecord.put("liquidatedCount", liquidatedLoans.size());
                txRecord.put("liquidatedLoans", liquidatedLoans);
                txRecord.put("status", "SUCCESS");
                transactions.add(txRecord);
                processedTxIds.add(txId);
            }

            result.put("status", "SUCCESS");
            result.put("message", "清算完成，共处理 " + liquidatedLoans.size() + " 笔逾期贷款");
            result.put("liquidatedLoans", liquidatedLoans);
        } catch (Exception e) {
            result.put("message", e.getMessage());
        }
        return result;
    }

    // 6. 更新房产估值（管理员操作）
    public static Map<String, Object> updatePropertyValuation(Map<String, Object> transaction, String signature) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "FAILED");

        // 提取交易字段（管理员、新估值、交易ID、时间戳）
        String admin = (String) transaction.get("admin");
        Long newValuation = (Long) transaction.get("newValuation");
        String txId = (String) transaction.get("txId");
        Long timestamp = (Long) transaction.get("timestamp");

        // 基础校验
        if (admin == null || newValuation == null || txId == null || timestamp == null) {
            result.put("message", "估值更新数据不完整");
            return result;
        }
        if (!PROPERTY_OWNER.equals(admin) || !accountKeys.containsKey(admin)) {
            result.put("message", "无权限更新估值（需管理员身份）");
            return result;
        }
        if (newValuation <= 0) {
            result.put("message", "新估值必须大于0");
            return result;
        }
        if (processedTxIds.contains(txId)) {
            result.put("message", "估值更新交易已处理");
            return result;
        }

        try {
            // 验证管理员签名
            String adminPubKey = Base64.getEncoder().encodeToString(accountKeys.get(admin).getPublic().getEncoded());
            if (!verifyTransaction(adminPubKey, transaction, signature)) {
                throw new SecurityException("管理员签名验证失败");
            }

            // 执行估值更新
            synchronized (PropertyMortgageLoanContract.class) {
                long oldValuation = (long) propertyInfo.get("currentValuation");
                propertyInfo.put("currentValuation", newValuation);
                // 更新代币价格（估值/总代币供应量）
                long newTokenPrice = newValuation / TOTAL_TOKEN_SUPPLY;
                propertyInfo.put("tokenPrice", newTokenPrice);
                // 更新市场信息
                marketInfo.put("lastValuationUpdateTime", timestamp);

                // 记录交易
                Map<String, Object> txRecord = new HashMap<>(transaction);
                txRecord.put("type", "VALUATION_UPDATE");
                txRecord.put("oldValuation", oldValuation);
                txRecord.put("newTokenPrice", newTokenPrice);
                txRecord.put("status", "SUCCESS");
                transactions.add(txRecord);
                processedTxIds.add(txId);
            }

            result.put("status", "SUCCESS");
            result.put("message", "房产估值更新成功");
            result.put("oldValuation", propertyInfo.get("currentValuation"));
            result.put("newValuation", newValuation);
            result.put("newTokenPrice", propertyInfo.get("tokenPrice"));
        } catch (Exception e) {
            result.put("message", e.getMessage());
        }
        return result;
    }

    // 辅助方法：更新代币价格（基于当前房产估值）
    private static void updateTokenPrice() {
        long currentValuation = (long) propertyInfo.get("currentValuation");
        long newTokenPrice = currentValuation / TOTAL_TOKEN_SUPPLY;
        propertyInfo.put("tokenPrice", newTokenPrice);
    }

    // 辅助方法：查找活跃贷款
    private static Map<String, Object> findActiveLoan(String loanId, String borrower) {
        for (Map<String, Object> loan : loans) {
            if (loanId.equals(loan.get("loanId"))
                    && borrower.equals(loan.get("borrower"))
                    && "ACTIVE".equals(loan.get("status"))) {
                return loan;
            }
        }
        return null;
    }

    // 辅助方法：生成贷款ID
    private static String generateLoanId(String borrower, long timestamp) {
        String data = borrower + "LOAN" + timestamp + (transactionCounter++);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash).substring(0, 20);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("生成贷款ID失败", e);
        }
    }

    // 生成交易ID
    public static String generateTxId(String address, String action) {
        String data = address + action + System.currentTimeMillis() + (transactionCounter++);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("生成交易ID失败", e);
        }
    }

    // 生成交易签名
    public static String signTransaction(String address, Map<String, Object> transaction) {
        if (!accountKeys.containsKey(address)) {
            throw new IllegalArgumentException("账户不存在: " + address);
        }

        try {
            String txData = serializeTransaction(transaction);
            PrivateKey privateKey = accountKeys.get(address).getPrivate();

            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(txData.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new RuntimeException("签名生成失败", e);
        }
    }

    // 验证交易签名
    public static boolean verifyTransaction(String publicKeyStr, Map<String, Object> transaction, String signatureStr) {
        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyStr);
            byte[] signatureBytes = Base64.getDecoder().decode(signatureStr);
            String txData = serializeTransaction(transaction);

            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));

            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(txData.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signatureBytes);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // 序列化交易数据
    private static String serializeTransaction(Map<String, Object> transaction) {
        List<String> parts = new ArrayList<>();
        List<String> keys = new ArrayList<>(transaction.keySet());
        Collections.sort(keys);

        for (String key : keys) {
            if (!"status".equals(key) && !"message".equals(key) && !"type".equals(key)) {
                parts.add(key + "=" + transaction.get(key));
            }
        }
        return String.join("&", parts);
    }

    // 查询质押余额
    public static long getStakedBalance(String address) {
        return stakedTokens.getOrDefault(address, 0L);
    }

    // 查询代币余额
    public static long getTokenBalance(String address) {
        return tokenBalances.getOrDefault(address, 0L);
    }

    // 查询资金余额
    public static long getFundBalance(String address) {
        return fundBalances.getOrDefault(address, 0L);
    }

    // 查询贷款信息
    public static List<Map<String, Object>> getLoanInfo(String borrower) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> loan : loans) {
            if (borrower.equals(loan.get("borrower"))) {
                result.add(new HashMap<>(loan));
            }
        }
        return result;
    }

    // 获取合约状态
    public static Map<String, Object> getContractStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("propertyInfo", new HashMap<>(propertyInfo));
        status.put("marketInfo", new HashMap<>(marketInfo));
        status.put("lenderFund", getFundBalance(LENDER_ROLE));
        status.put("activeLoans", loans.stream().filter(loan -> "ACTIVE".equals(loan.get("status"))).count());
        return status;
    }

    // 测试入口
    public static void main(String[] args) {
        System.out.println("=== 启动房产质押借贷RWA合约 ===");

        // 1. 创建账户（管理员、资金池、用户）
        String admin = PROPERTY_OWNER;
        String lender = LENDER_ROLE;
        String alice = "Alice";
        String bob = "Bob";
        try {
            createAccount(admin, "ADMIN");
            createAccount(lender, "LENDER");
            createAccount(alice, "USER");
            createAccount(bob, "USER");
            System.out.println("\n=== 账户创建成功 ===");
            System.out.println("管理员: " + admin + "，初始代币: " + getTokenBalance(admin));
            System.out.println("资金池: " + lender + "，初始资金: " + getFundBalance(lender));

            // 给用户充值资金
            fundBalances.put(alice, 5000000L);  // 500万
            fundBalances.put(bob, 8000000L);    // 800万
            System.out.println(alice + " 初始资金: " + getFundBalance(alice));
            System.out.println(bob + " 初始资金: " + getFundBalance(bob));
        } catch (Exception e) {
            System.err.println("账户创建失败: " + e.getMessage());
            return;
        }

        // 2. Alice向管理员购买代币
        System.out.println("\n=== Alice 购买代币 ===");
        long aliceBuyTokens = 100000L; // 10万个
        long tokenPrice = (long) propertyInfo.get("tokenPrice");
        String aliceTradeTxId = generateTxId(alice, "TRADE");
        long aliceTradeTime = System.currentTimeMillis();
        Map<String, Object> aliceTradeTx = new HashMap<>();
        aliceTradeTx.put("seller", admin);
        aliceTradeTx.put("buyer", alice);
        aliceTradeTx.put("tokenAmount", aliceBuyTokens);
        aliceTradeTx.put("unitPrice", tokenPrice);
        aliceTradeTx.put("txId", aliceTradeTxId);
        aliceTradeTx.put("timestamp", aliceTradeTime);

        String aliceTradeSig = signTransaction(admin, aliceTradeTx); // 卖方签名
        Map<String, Object> aliceTradeResult = tradeTokens(aliceTradeTx, aliceTradeSig);
        System.out.println("交易结果: " + aliceTradeResult.get("message"));
        System.out.println(alice + " 代币余额: " + getTokenBalance(alice));
        System.out.println(alice + " 资金余额: " + getFundBalance(alice));

        // 3. Alice质押代币借款
        System.out.println("\n=== Alice 质押借款 ===");
        long stakeAmount = 50000L; // 质押5万个代币
        long borrowAmount = 150000L; // 借款15万（抵押率=15万/(5万*5)=60%，低于70%上限）
        String aliceBorrowTxId = generateTxId(alice, "BORROW");
        long aliceBorrowTime = System.currentTimeMillis();
        Map<String, Object> aliceBorrowTx = new HashMap<>();
        aliceBorrowTx.put("borrower", alice);
        aliceBorrowTx.put("stakeTokenAmount", stakeAmount);
        aliceBorrowTx.put("borrowFundAmount", borrowAmount);
        aliceBorrowTx.put("txId", aliceBorrowTxId);
        aliceBorrowTx.put("timestamp", aliceBorrowTime);

        String aliceBorrowSig = signTransaction(alice, aliceBorrowTx);
        Map<String, Object> aliceBorrowResult = mortgageBorrow(aliceBorrowTx, aliceBorrowSig);
        System.out.println("借款结果: " + aliceBorrowResult.get("message"));
        System.out.println(alice + " 质押余额: " + getStakedBalance(alice));
        System.out.println(alice + " 可用资金: " + getFundBalance(alice));

        // 4. 房产估值下跌（触发清算风险）
        System.out.println("\n=== 管理员更新房产估值（下跌） ===");
        long newValuation = 400000000L; // 估值从5亿降至4亿（代币价格从5元降至4元）
        String updateTxId = generateTxId(admin, "UPDATE_VALUATION");
        long updateTime = System.currentTimeMillis();
        Map<String, Object> updateTx = new HashMap<>();
        updateTx.put("admin", admin);
        updateTx.put("newValuation", newValuation);
        updateTx.put("txId", updateTxId);
        updateTx.put("timestamp", updateTime);

        String updateSig = signTransaction(admin, updateTx);
        Map<String, Object> updateResult = updatePropertyValuation(updateTx, updateSig);
        System.out.println("估值更新结果: " + updateResult.get("message"));
        System.out.println("新代币价格: " + propertyInfo.get("tokenPrice") + " 元");


        // 4.5 房产估值继续下跌（代币价格降至2.5元，触发清算）
        System.out.println("\n=== 管理员再次更新房产估值（继续下跌至2.5元） ===");
        long lowerValuation = 250000000L; // 估值2.5亿（2.5亿 / 1亿代币 = 2.5元/个）
        String lowerUpdateTxId = generateTxId(admin, "LOWER_VALUATION");
        long lowerUpdateTime = System.currentTimeMillis();
        Map<String, Object> lowerUpdateTx = new HashMap<>();
        lowerUpdateTx.put("admin", admin);
        lowerUpdateTx.put("newValuation", lowerValuation);
        lowerUpdateTx.put("txId", lowerUpdateTxId);
        lowerUpdateTx.put("timestamp", lowerUpdateTime);

        String lowerUpdateSig = signTransaction(admin, lowerUpdateTx);
        Map<String, Object> lowerUpdateResult = updatePropertyValuation(lowerUpdateTx, lowerUpdateSig);
        System.out.println("二次估值更新结果: " + lowerUpdateResult.get("message"));
        System.out.println("最新代币价格: " + propertyInfo.get("tokenPrice") + " 元");




        // 5. 管理员执行清算（抵押率低于60%）
        System.out.println("\n=== 管理员执行清算 ===");
        String liquidateTxId = generateTxId(admin, "LIQUIDATE");
        long liquidateTime = System.currentTimeMillis();
        Map<String, Object> liquidateTx = new HashMap<>();
        liquidateTx.put("admin", admin);
        liquidateTx.put("txId", liquidateTxId);
        liquidateTx.put("timestamp", liquidateTime);

        String liquidateSig = signTransaction(admin, liquidateTx);
        Map<String, Object> liquidateResult = liquidateLoans(liquidateTx, liquidateSig);
        System.out.println("清算结果: " + liquidateResult.get("message"));
        System.out.println("被清算的贷款: " + liquidateResult.get("liquidatedLoans"));

        // 6. 查看最终状态
        System.out.println("\n=== 最终合约状态 ===");
        Map<String, Object> finalStatus = getContractStatus();
        Object propertyObj = finalStatus.get("propertyInfo");
        if (propertyObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> propertyMap = (Map<String, Object>) propertyObj;
            System.out.println("房产当前估值: " + propertyMap.get("currentValuation"));
        } else {
            System.out.println("房产当前估值: 获取失败（数据格式错误）");
        }

        System.out.println("资金池剩余资金: " + finalStatus.get("lenderFund"));
        System.out.println("Alice 代币余额: " + getTokenBalance(alice)); // 质押代币已被清算



    }
}