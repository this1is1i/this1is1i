import org.Zyuhang.ServiceL.MyServiceA;
import org.Zyuhang.ServiceM.ServiceBus;
import org.junit.Test;

public class MyServiceTest {
    @Test
    public void test1(){
        ServiceBus busExample = new ServiceBus(new MyServiceA());
        try {
            busExample.doS();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void test2(){
        ServiceBus busExample = new ServiceBus(new MyServiceA());
        try {
            busExample.doS(1,2,3);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void test3(){
        MyServiceA ma = new MyServiceA("332288");
        ma.execute(1,2,3);
    }

    @Test
    public void test4(){
        MyServiceA ma = new MyServiceA("332288");
        ma.executeWithoutLogin();
    }

    @Test
    public void test5(){
        ServiceBus busExample = new ServiceBus();
        busExample.setService(new MyServiceA());
        try {
            busExample.doS(1,2,3);
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
