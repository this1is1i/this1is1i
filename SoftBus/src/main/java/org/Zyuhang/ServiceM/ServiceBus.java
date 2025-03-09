package org.Zyuhang.ServiceM;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ServiceBus {
    //使用类列表与类名集合来存储服务，加快比对速度同时减少消耗的内存
    private final List<Class<? extends IServices>> serviceClasses = new ArrayList<>();
    private final Set<String> targetClassNameSet = new HashSet<>();

    public ServiceBus() {
        loadServicesFromConfig();
        if (serviceClasses.isEmpty()) {
            throw new IllegalArgumentException("没有注册服务,请考虑检查配置文件");
        }
    }
    public ServiceBus(IServices... services) {
        for (IServices service : services) {
            register(service);
        }
        if (serviceClasses.isEmpty()) {
            throw new IllegalArgumentException("没有注册服务");
        } else {
            serviceClasses.forEach(serviceClass -> targetClassNameSet.add(serviceClass.getName()));
        }
    }
    //编写此方法以适用于服务众多时的首次调用检索速度
    /**
      *此处问题在于是否会因反复创建对象而导致速度变慢
      * 可以考虑使用单例模式、静态内部类来解决此问题
      * 以及配置文件的管理同样需要严格规范
      * 或许可以再添加一个serviceClassNameSet来存储类名，然后在注册时将类名添加到serviceClassNameSet中？
     */
    private void loadServicesFromConfig() {
        String configFilePath = "src/main/resources/services.txt";
        try (BufferedReader reader = new BufferedReader(new FileReader(configFilePath))) {
            String line;
                        while ((line = reader.readLine()) != null) {
                try {
                    // 尝试加载服务类
                    Class<? extends IServices> serviceClass = (Class<? extends IServices>) Class.forName(line.trim());
                    // 检查服务类是否实现了 IServices 接口
                    if (IServices.class.isAssignableFrom(serviceClass)) {
                        serviceClasses.add(serviceClass);
                    } else {
                        System.err.println("类 " + line.trim() + " 未实现 IServices 接口，跳过加载。");
                    }
                } catch (ClassNotFoundException e) {
                    // 处理类未找到的异常
                    System.err.println("未找到类: " + line.trim() + "，错误信息: " + e.getMessage());
                } catch (ClassCastException e) {
                    // 处理类转换异常
                    System.err.println("类 " + line.trim() + " 无法转换为 IServices 类型，错误信息: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            // 处理文件读取异常
            System.err.println("无法读取配置文件: " + e.getMessage());
        }
    }

    /**
     * 由下部分帮助文档可知，此方法newInstance新建出来的对象是没有被初始化的，
     * 所以需要使用getDeclaredConstructor().newInstance()来获取实例
     * 但是这会导致只能使用无参构造器，因为在注册时使用的是getClass()，导致参数实际上并没有传递进来
     * 可以尝试将ServiceBus busExample = new ServiceBus(new MyServiceA())换为ServiceBus busExample = new ServiceBus(new MyServiceA("123456789"));以验证
     * 因此借助第二个含参构造模拟登陆情况
     *   反射获取实例
     *  Uses the constructor represented by this {@code Constructor} object to
     *  create and initialize a new instance of the constructor's
     *  declaring class, with the specified initialization parameters.
     *  Individual parameters are automatically unwrapped to match
     *  primitive formal parameters, and both primitive and reference
     *  parameters are subject to method invocation conversions as necessary.
     *
     *  <p>If the number of formal parameters required by the underlying constructor
     *  is 0, the supplied {@code initargs} array may be of length 0 or null.
     *
     *  <p>If the constructor's declaring class is an inner class in a
     *  non-static context, the first argument to the constructor needs
     *  to be the enclosing instance; see section 15.9.3 of
     *  <cite>The Java&trade; Language Specification</cite>.
     *
     *  <p>If the required access and argument checks succeed and the
     *  instantiation will proceed, the constructor's declaring class
     *  is initialized if it has not already been initialized.
     *
     *  <p>If the constructor completes normally, returns the newly
     *  created and initialized instance.
     *  @ param init args array of objects to be passed as arguments to
     *  the constructor call; values of primitive types are wrapped in
     *  a wrapper object of the appropriate type (e.g. a {@code float}
     *  in a {@link java.lang.Float Float})
     *
     */
    public void doS(Object... args) throws Exception {
        for (Class<? extends IServices> serviceClass : serviceClasses) {
            if (targetClassNameSet.contains(serviceClass.getName())) {
                IServices service = serviceClass.getDeclaredConstructor(Boolean.class).newInstance(true);
                service.execute(args);
            }
        }
    }

    private void register(IServices service) {
        if(!serviceClasses.contains(service.getClass()))
            serviceClasses.add(service.getClass());
    }

    private void remove(IServices service) {
        serviceClasses.remove(service.getClass());
    }

    public void setService(IServices... services) {
        for (IServices service : services) {
            register(service);
            targetClassNameSet.add(service.getClass().getName());
        }
        if (serviceClasses.isEmpty()) {
            throw new IllegalArgumentException("没有注册服务");
        }
    }

    public void removeService(IServices... services){
        for (IServices service: services) {
            remove(service);
        }
    }

    public void OnlyReserveService(IServices service){
        targetClassNameSet.clear();
        targetClassNameSet.add(service.getClass().getName());
    }
}
