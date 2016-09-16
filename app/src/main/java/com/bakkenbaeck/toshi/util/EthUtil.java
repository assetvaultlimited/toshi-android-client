package com.bakkenbaeck.toshi.util;


import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.DecimalFormat;

public class EthUtil {

    private static final BigDecimal weiToEthRatio = new BigDecimal("1000000000000000000");
    private static final DecimalFormat formatting = new DecimalFormat("#0.##########");

    public static String weiToEthString(final BigInteger wei) {
        final BigDecimal bd = weiToEth(wei);
        formatting.setRoundingMode(RoundingMode.FLOOR);
        return formatting.format(bd);
    }

    public static BigDecimal weiToEth(final BigInteger wei) {
        return new BigDecimal(wei).divide(weiToEthRatio);
    }
}
