package io.github.pods.core;

/** 状态栏优先显示耳机较低电量，缺失时保留未知，不使用充电盒冒充。 */
public final class BatteryStatus {
    private BatteryStatus() {}

    public static Integer level(Integer left, Integer right, Integer system) {
        // 分支返回，避免数值三元表达式将未知的 Integer 隐式拆箱。
        if (left != null && right != null) return Math.min(left, right);
        if (left != null) return left;
        if (right != null) return right;
        return system;
    }
}
