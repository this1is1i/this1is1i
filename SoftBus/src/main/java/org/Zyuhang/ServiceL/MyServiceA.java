package org.Zyuhang.ServiceL;

import org.Zyuhang.ServiceM.IServices;

public class MyServiceA implements IServices {
    private final String AvailableParameters;
    private boolean isLogin = false;

    public MyServiceA() {
        this.AvailableParameters = "默认参数";
    }

    //此处第一个含参构造方法目的是为了验证ServiceBus的参数传递问题，以及不经总线直接调用时的参数传递问题，在理想状态下，此类操作会被日志记录
    public MyServiceA(String AP) {
        this.AvailableParameters = AP;
    }

    //第二个含参构造适用于模拟登陆验证
    public MyServiceA(Boolean isLogin) {
        this.isLogin = isLogin;
        this.AvailableParameters = "默认参数";
    }

    private void doSomething(Object... args) {
        for (Object arg : args) {
            System.out.println(arg);
        }
        System.out.println("————————————————Hello world!——————————————");
        System.out.println("参数是:" + AvailableParameters);
        if (args.length >= 2 &&
                (args[0] instanceof Integer || args[0] instanceof Float) &&
                (args[1] instanceof Integer || args[1] instanceof Float)) {

            double sum = 0;
            if (args[0] instanceof Integer) {
                sum += (Integer) args[0];
            } else {
                sum += (Float) args[0];
            }

            if (args[1] instanceof Integer) {
                sum += (Integer) args[1];
            } else {
                sum += (Float) args[1];
            }

            System.out.println(sum);
            System.out.println("算完啦！");
        } else {
            System.out.println("输出完成");
        }
    }

    @Override
    public void execute(Object... args) {
        //此处可以添加登陆验证，日志记录等行为
        if (!isLogin) {
            //此处取巧模拟未登录,本意应使用令牌等机制
            System.out.println("未登录,无资格调用此服务，请使用总线");
            return;
        }
        doSomething(args);
    }

    public void executeWithoutLogin() {
        //此方法为满足在不使用总线的情况下调用某些简单服务的需求
        System.out.println("此时参数是:" + AvailableParameters);
    }
}
