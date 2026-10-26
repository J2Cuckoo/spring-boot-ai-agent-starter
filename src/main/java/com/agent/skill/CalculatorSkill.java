package com.agent.skill;

import java.util.Map;

/**
 * 内置技能：安全四则运算计算器（递归下降解析，不使用 eval）。
 */
public final class CalculatorSkill implements Skill {

    @Override
    public String id() {
        return "calculator";
    }

    @Override
    public String name() {
        return "计算器";
    }

    @Override
    public String description() {
        return "计算四则运算表达式，支持 + - * / % ^ 与括号，如 (1+2)*3^2";
    }

    @Override
    public SkillResult execute(Map<String, Object> args, SkillProgress progress) {
        Object expr = args == null ? null : args.get("expression");
        if (expr == null || expr.toString().isBlank()) {
            return SkillResult.fail("缺少参数 expression");
        }
        progress.report("解析表达式", 30);
        try {
            double value = new Parser(expr.toString()).parse();
            progress.report("计算完成", 100);
            return SkillResult.ok(format(value));
        } catch (Exception e) {
            return SkillResult.fail("表达式非法: " + e.getMessage());
        }
    }

    private static String format(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    /** 递归下降解析器。 */
    static final class Parser {
        private final String s;
        private int pos = -1;
        private int ch;

        Parser(String s) {
            this.s = s;
        }

        double parse() {
            next();
            double v = expr();
            if (ch != -1) {
                throw new IllegalArgumentException("多余字符: " + (char) ch);
            }
            return v;
        }

        private void next() {
            ch = ++pos < s.length() ? s.charAt(pos) : -1;
        }

        private boolean eat(int c) {
            while (ch == ' ') {
                next();
            }
            if (ch == c) {
                next();
                return true;
            }
            return false;
        }

        private double expr() {
            double v = term();
            for (; ; ) {
                if (eat('+')) {
                    v += term();
                } else if (eat('-')) {
                    v -= term();
                } else {
                    return v;
                }
            }
        }

        private double term() {
            double v = factor();
            for (; ; ) {
                if (eat('*')) {
                    v *= factor();
                } else if (eat('/')) {
                    v /= factor();
                } else if (eat('%')) {
                    v %= factor();
                } else {
                    return v;
                }
            }
        }

        private double factor() {
            double base = unary();
            if (eat('^')) {
                return Math.pow(base, factor());
            }
            return base;
        }

        private double unary() {
            if (eat('-')) {
                return -unary();
            }
            if (eat('+')) {
                return unary();
            }
            double v;
            int start = pos;
            if (eat('(')) {
                v = expr();
                if (!eat(')')) {
                    throw new IllegalArgumentException("缺少右括号 at " + start);
                }
            } else if (ch >= '0' && ch <= '9' || ch == '.') {
                while (ch >= '0' && ch <= '9' || ch == '.') {
                    next();
                }
                v = Double.parseDouble(s.substring(start, pos));
            } else {
                throw new IllegalArgumentException("非法字符 at " + pos + ": " + (char) ch);
            }
            return v;
        }
    }
}
