package com.ooohyg.agent.workflow;

import java.util.regex.Pattern;

/** Parses and renders the small, side-effect-free language used by the fixture edit tool. */
final class PaginationExpression {
    private static final Pattern TOKEN = Pattern.compile("[ \\t]*(Math\\.min|Math\\.max|limit|0|[1-9][0-9]*|[(),])");
    private final String text;
    private int offset;

    private PaginationExpression(String text) {
        this.text = text;
    }

    static String render(String text) {
        if (text.length() > 160) throw invalid();
        var parser = new PaginationExpression(text.strip());
        String result = parser.expression(0);
        if (parser.offset != parser.text.length()) throw invalid();
        return result;
    }

    private String expression(int depth) {
        if (depth > 6) throw invalid();
        String token = next();
        return switch (token) {
            case "limit" -> token;
            case "Math.min", "Math.max" -> {
                expect("(");
                String left = expression(depth + 1);
                expect(",");
                String right = expression(depth + 1);
                expect(")");
                yield token + "(" + left + ", " + right + ")";
            }
            case "(" -> {
                String inner = expression(depth + 1);
                expect(")");
                yield "(" + inner + ")";
            }
            default -> {
                try {
                    yield Integer.toString(Integer.parseInt(token));
                } catch (NumberFormatException e) {
                    throw invalid();
                }
            }
        };
    }

    private void expect(String token) {
        if (!next().equals(token)) throw invalid();
    }

    private String next() {
        var matcher = TOKEN.matcher(text).region(offset, text.length());
        if (!matcher.lookingAt()) throw invalid();
        offset = matcher.end();
        return matcher.group(1);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Use only limit, non-negative int literals, parentheses, Math.min and Math.max");
    }
}
