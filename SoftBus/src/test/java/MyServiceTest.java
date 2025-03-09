import org.Zyuhang.ServiceL.MyServiceA;
import org.Zyuhang.ServiceL.MyServiceB;
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

    @Test
    public void test6(){
        ServiceBus busExample = new ServiceBus();
        busExample.setService(new MyServiceA());
        busExample.setService(new MyServiceB());
        try{
            busExample.doS(1,2);
        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    @Test
    public void test7() throws Exception {
        MyServiceA MA = new MyServiceA();
        MyServiceB MB = new MyServiceB();
        ServiceBus busExample = new ServiceBus(MA,MB);
        busExample.doS(1,2);
        busExample.removeService(MA);
        System.out.println("*****************************************************");
        busExample.doS(1,2);
    }

    @Test
    public void test8() throws Exception {
        MyServiceA MA = new MyServiceA();
        MyServiceB MB = new MyServiceB();
        ServiceBus busExample = new ServiceBus(MA,MB);
        busExample.doS(1,2);
        busExample.OnlyReserveService(MA);
        System.out.println("*****************************************************");
        busExample.doS(1,2);
    }
}
