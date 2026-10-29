package com.agent.contract;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * JSON 输出字段注解：仅用于 {@code conversation.write(Class)} 的目标对象字段上。
 * <p>目标类只要声明了字段，就必须至少有一个字段标注本注解（否则 {@code write(Class)} 直接抛
 * {@link IllegalStateException}）；大模型只被要求返回标注了本注解的字段。</p>
 *
 * <pre>{@code
 * public class Weather {
 *     @AgentJsonFormatFiled(desc = "城市名称")
 *     private String city;
 *
 *     @AgentJsonFormatFiled(desc = "观测日期", format = "yyyy-MM-dd")
 *     private LocalDate date;
 * }
 * }</pre>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentJsonFormatFiled {

    /** 字段含义（必填，非空）：让大模型知道该字段是什么，才能精准返回。 */
    String desc();

    /** 内容格式（可选）：要求大模型按指定格式返回该字段值，如时间格式 yyyy-MM-dd。 */
    String format() default "";
}
