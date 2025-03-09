# 接口文档

## 项目简介

本项目是一个基于Java的服务总线（ServiceBus）系统，用于动态加载和管理服务。服务总线通过读取配置文件中的服务类名，使用反射机制实例化并执行这些服务。

## 主要组件

- `IServices`：服务接口，所有服务类必须实现该接口。
- `ServiceBus`：服务总线类，负责加载、注册和管理服务。
- `services.txt`：配置文件，存储所有服务类的全限定名。

## 接口说明

### IServices 接口

```java
package org.Zyuhang.ServiceM;

public interface IServices {
    void execute(Object... args) throws Exception;
}
```

- `execute(Object... args)`：执行服务的主要方法。参数 `args` 可以是任意数量和类型的对象。

### ServiceBus 类

```java
package org.Zyuhang.ServiceM;

import java.util.List;
import java.util.Set;

public class ServiceBus {
    private final List<Class<? extends IServices>> serviceClasses;
    private final Set<String> targetClassNameSet;

    public ServiceBus();
    public ServiceBus(IServices... services);
    
    public void doS(Object... args) throws Exception;
    private void register(IServices service);
    private void remove(IServices service);
    public void setService(IServices... services);
    public void removeService(IServices... services);
    public void OnlyReserveService(IServices service);
}
```

- `ServiceBus()`：默认构造函数，从配置文件加载服务。
- `ServiceBus(IServices... services)`：带参数的构造函数，用于手动注册服务。适用于服务数量较少的情况。
- `doS(Object... args)`：执行所有已注册的服务。
- `register(IServices service)`：注册单个服务。（私有的）
- `remove(IServices service)`：移除单个服务。（私有的）
- `setService(IServices... services)`：在没有使用带参构造函数时，必须使用此方法批量注册想要使用的个人服务。
- `removeService(IServices... services)`：从已保存的所有服务类列表中移除传入的服务类。
- `OnlyReserveService(IServices service)`：将要执行的所有服务类的列表清空，只保留传入的目标服务类。

## 使用说明

### 配置文件

在 `src/main/resources` 目录下创建 `services.txt` 文件，每行包含一个服务类的全限定名，例如：

```
org.Zyuhang.ServiceL.MyServiceA
```
注意：此配置文件本意为服务器所维护

### 注册服务

可以通过以下两种方式注册服务：

1. **通过配置文件**：在 `services.txt` 中添加服务类的全限定名，`ServiceBus` 将在启动时自动加载这些服务。
2. **手动注册**：使用 `ServiceBus` 的带参数构造函数或 `setService` 方法手动注册服务。（既可以一次传入多个服务类，也支持多次传入不同服务类）

### 执行服务

调用 `ServiceBus` 的 `doS` 方法，传递所需的参数，执行所有已注册的服务。

```java
ServiceBus busExample = new ServiceBus();
busExample.doS("param1", 123, true); //参数列表取决于服务的具体实现
```

## 注意事项

- 确保所有服务类实现了 `IServices` 接口。
- 配置文件中的类名必须是全限定名，且类必须存在于项目的类路径中。
- 使用反射机制实例化服务类时，确保类有适当的构造函数。

## 示例

以下是一个简单的服务类示例：

```java
package org.Zyuhang.ServiceL;

import org.Zyuhang.ServiceM.IServices;

public class MyServiceA implements IServices {
    @Override
    public void execute(Object... args) throws Exception {
        // 服务逻辑
        // 但更推荐在此处处理日志等行为，添加登录验证等，再额外调用doSomething方法
    }
}
```

## 贡献指南

欢迎对项目进行贡献。请遵循以下步骤：

1. Fork [项目](https://github.com/this1is1i/this1is1i/tree/main/SoftBus)到你的GitHub账户。
2. 创建一个新的分支进行修改。
3. 提交你的改动并发起一个Pull Request。

## 许可证

本项目采用MIT许可证。版权归Zyuhang所有。