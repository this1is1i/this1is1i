package org.Zyuhang.ServiceL;

import org.Zyuhang.ServiceM.IServices;

import java.util.Arrays;

public class MyServiceB implements IServices {
    private boolean isLogin = false;

    public MyServiceB() {}
    public MyServiceB(Boolean isLogin) {
        this.isLogin = isLogin;
    }

    @Override
    public void execute(Object... args) throws Exception {
        System.out.println(Arrays.toString(args));
    }
}
