package com.amber.speaker.fn;

/**
 * 四个函数式接口，和 {@code java.util.function} 里的同名同形。
 *
 * <p>为什么不直接用 JDK 的那几个：音箱是 <b>Android 5.1（API 22）</b>，
 * 而 {@code java.util.function.*} 是 <b>API 24</b> 才进 Android 的。
 * 编译时对着 android-33 的 android.jar 完全没问题，装到机器上启动就崩：
 *
 * <pre>
 * java.lang.NoClassDefFoundError: com.amber.speaker.MainActivity$1
 *     at com.amber.speaker.MainActivity.onCreate(MainActivity.java:115)
 * </pre>
 *
 * 报的是匿名类找不到，其实是它实现的接口不存在——类加载器先解析父接口，解析不到就报这个，
 * 很容易误判成「打包漏了 class」。
 *
 * <p>D8 只脱糖「语法」（lambda、默认方法），不脱糖「库 API」，除非额外塞 desugar_jdk_libs；
 * 这个工程是裸 aapt2 + d8，没有那套东西。所以自己写四个二十行的接口最省事。
 */
public final class Suppliers {

    private Suppliers() {
    }

    /** 同 {@code java.util.function.IntSupplier}。 */
    public interface IntSupplier {
        int getAsInt();
    }

    /** 同 {@code java.util.function.LongSupplier}。 */
    public interface LongSupplier {
        long getAsLong();
    }

    /** 同 {@code java.util.function.BooleanSupplier}。 */
    public interface BooleanSupplier {
        boolean getAsBoolean();
    }

    /** 同 {@code java.util.function.Consumer}。 */
    public interface Consumer<T> {
        void accept(T value);
    }
}
