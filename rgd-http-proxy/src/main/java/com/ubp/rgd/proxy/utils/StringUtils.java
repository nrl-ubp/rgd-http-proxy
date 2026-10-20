package com.ubp.rgd.proxy.utils;

public class StringUtils {

    /**
     * Returns the first 5 characters of a string. If the string is less than 5 characters, then fill with '#'
     * @param s the string to get 5 characters,
     * @return first 5 characters filled with hashes if too small. Null string return 5 hashes
     */
    public static String firstFiveWithHash(String s) {
        return firstNCharWithReplacement(s, 5, '#');
    }

    /**
     * Get the first n chars of string s filled with replacements chars if s is too small.
     * @param s the string to get first n chars
     * @param n number of chars from s
     * @param replacement replacement char if s is too smll
     * @return first n chars of s replaced with replacement char if too small.
     */
    public static String firstNCharWithReplacement(String s, int n, char replacement) {
        if (s == null) s = "";
        return (s + String.valueOf(replacement).repeat(Math.max(0, n))).substring(0, n);
    }

}
