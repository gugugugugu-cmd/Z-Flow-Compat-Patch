package io.relimus.zflow.compat.xposed.util

import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * 极简反射工具。
 *
 * 现代 Xposed API（102）不再提供 `XposedHelpers`，所以这些补丁自己实现所需的一小部分：
 * 找类、找方法（按参数类型/个数）、读写字段、调用方法。
 *
 * 全部方法"找不到就返回 null"，不抛异常——补丁要能在目标应用升级、混淆名变化时
 * 安静地降级为"功能不可用"，而不是崩溃。
 */
object Reflect {

    /** 按名称找类，支持在多个 classloader 上尝试。 */
    fun findClass(name: String, classLoader: ClassLoader?): Class<*>? {
        val loaders = listOfNotNull(classLoader, Thread.currentThread().contextClassLoader, Reflect::class.java.classLoader)
        for (cl in loaders) {
            val c = runCatching { Class.forName(name, false, cl) }.getOrNull()
            if (c != null) return c
        }
        return null
    }

    /** 在一个类及其父类链上按名称+参数个数找方法。 */
    fun findMethod(clazz: Class<*>, name: String, paramCount: Int? = null): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name != name) continue
                if (paramCount != null && m.parameterCount != paramCount) continue
                return m
            }
            c = c.superclass
        }
        return null
    }

    /** 在一个类及其父类链上按名称找**全部**同名方法（含重载）。 */
    fun findMethods(clazz: Class<*>, name: String): List<Method> {
        val out = mutableListOf<Method>()
        var c: Class<*>? = clazz
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name == name) out += m
            }
            c = c.superclass
        }
        return out
    }

    /**
     * 精确匹配：方法名 + 参数类型逐个可赋值。
     * 优先用它，避免重载命中错误的方法。
     */
    fun findMethodExact(clazz: Class<*>, name: String, vararg params: Class<*>): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            val m = runCatching { c.getDeclaredMethod(name, *params) }.getOrNull()
            if (m != null) return m
            c = c.superclass
        }
        return null
    }

    /** 让可执行对象可被调用（含非 public 成员）。 */
    fun makeAccessible(e: Executable?): Executable? {
        if (e == null) return null
        runCatching { e.isAccessible = true }
        return e
    }

    /** 调用方法；失败返回 null。 */
    fun callMethod(target: Any?, method: Method?, vararg args: Any?): Any? {
        if (target == null || method == null) return null
        return runCatching {
            method.isAccessible = true
            method.invoke(target, *args)
        }.getOrNull()
    }

    /** 调用静态方法（[callMethod] 不接受 null target，静态调用走这里）；失败返回 null。 */
    fun callStatic(method: Method?, vararg args: Any?): Any? {
        if (method == null) return null
        return runCatching {
            method.isAccessible = true
            method.invoke(null, *args)
        }.getOrNull()
    }

    /** 在类继承链上按名称读取字段。 */
    fun getField(target: Any?, name: String): Any? {
        if (target == null) return null
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val f = runCatching { c.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                return runCatching {
                    f.isAccessible = true
                    f.get(target)
                }.getOrNull()
            }
            c = c.superclass
        }
        return null
    }

    /** 写字段；成功返回 true。 */
    fun setField(target: Any?, name: String, value: Any?): Boolean {
        if (target == null) return false
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val f = runCatching { c.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                return runCatching {
                    f.isAccessible = true
                    f.set(target, value)
                    true
                }.getOrDefault(false)
            }
            c = c.superclass
        }
        return false
    }

    /** 读静态字段。 */
    fun getStaticField(clazz: Class<*>?, name: String): Any? {
        if (clazz == null) return null
        return runCatching {
            val f = clazz.getDeclaredField(name)
            f.isAccessible = true
            f.get(null)
        }.getOrNull()
    }

    /** 读静态 int 字段，找不到返回 null。 */
    fun getStaticInt(clazz: Class<*>?, name: String): Int? {
        return (getStaticField(clazz, name) as? Int)
    }
}
