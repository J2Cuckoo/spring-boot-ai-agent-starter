package com.agent.output;

import com.agent.contract.AgentJsonFormatFiled;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 扫描目标类的 {@link AgentJsonFormatFiled} 注解，生成注入给大模型的 JSON 字段约束。
 *
 * <p>规则：目标类只要声明了字段（本类或超类），就必须至少一个字段带注解，否则抛
 * {@link IllegalStateException}；模型只被要求返回带注解的字段。Map/集合/基本类型等无字段
 * 目标不受此约束（沿用通用 JSON 指令）。</p>
 */
public final class AgentJsonSchema {

    private AgentJsonSchema() {
    }

    /**
     * 生成字段约束 system 指令；目标无声明字段（Map 等）返回 null（走通用 JSON 指令）。
     *
     * @throws IllegalStateException 声明了字段但没有任何 @AgentJsonFormatFiled
     * @throws IllegalArgumentException desc 为空
     */
    public static String schemaInstructionFor(Class<?> type) {
        if (type == null || isNonPojo(type)) {
            return null;
        }
        List<Field> annotated = annotatedFields(type);
        List<Field> declared = declaredFields(type);
        if (declared.isEmpty()) {
            return null;
        }
        if (annotated.isEmpty()) {
            throw new IllegalStateException("类 " + type.getName()
                    + " 用于 JSON 输出（write(Class)）时字段必须标注 @AgentJsonFormatFiled"
                    + "（desc 必填，可加 format），未标注任何字段");
        }
        StringBuilder sb = new StringBuilder("请输出 JSON 对象，只包含以下字段（不要输出其他字段）：\n");
        for (Field f : annotated) {
            AgentJsonFormatFiled a = f.getAnnotation(AgentJsonFormatFiled.class);
            String desc = a.desc();
            if (desc == null || desc.isBlank()) {
                throw new IllegalArgumentException("字段 " + type.getSimpleName() + "." + f.getName()
                        + " 的 @AgentJsonFormatFiled.desc 不能为空");
            }
            sb.append("- ").append(f.getName())
                    .append("（").append(jsonTypeOf(f.getType()));
            sb.append("）：").append(desc.trim());
            if (!a.format().isBlank()) {
                sb.append("；格式必须为 ").append(a.format().trim());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 本类 + 超类的声明字段（排除 static/Transient）。 */
    private static List<Field> declaredFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && !java.lang.reflect.Modifier.isTransient(f.getModifiers())) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    private static List<Field> annotatedFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Field f : declaredFields(type)) {
            if (f.isAnnotationPresent(AgentJsonFormatFiled.class)) {
                out.add(f);
            }
        }
        return out;
    }

    /** Map/集合/基本类型/字符串/日期等容器或标量目标不按 POJO 约束。 */
    private static boolean isNonPojo(Class<?> type) {
        return type.isPrimitive() || type.isArray()
                || CharSequence.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type)
                || Boolean.class == type
                || Character.class == type
                || java.util.Map.class.isAssignableFrom(type)
                || java.util.Collection.class.isAssignableFrom(type)
                || LocalDate.class == type || LocalDateTime.class == type
                || Date.class == type || java.time.Instant.class == type
                || Enum.class.isAssignableFrom(type);
    }

    private static String jsonTypeOf(Class<?> t) {
        if (t == String.class || CharSequence.class.isAssignableFrom(t)) {
            return "字符串";
        }
        if (t == Integer.class || t == int.class || t == Long.class || t == long.class
                || t == Short.class || t == short.class || t == Byte.class || t == byte.class) {
            return "整数";
        }
        if (t == Double.class || t == double.class || t == Float.class || t == float.class
                || Number.class.isAssignableFrom(t)) {
            return "数值";
        }
        if (t == Boolean.class || t == boolean.class) {
            return "布尔";
        }
        if (t == LocalDate.class || t == LocalDateTime.class || t == Date.class
                || t == java.time.Instant.class) {
            return "时间/日期";
        }
        if (java.util.List.class.isAssignableFrom(t)) {
            return "字符串数组";
        }
        return "对象";
    }
}
