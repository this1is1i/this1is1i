package org.Zyuhang.ServiceM;

public interface IServices {
    //采用间接调用的方式，使得个人服务处的处理更加灵活，同时避免接口直接暴露在外部
    //采用可变的参数列表，适应更多情况
    void execute(Object... args) throws Exception;
}
