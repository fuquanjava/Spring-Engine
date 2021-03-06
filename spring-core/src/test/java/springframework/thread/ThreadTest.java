package springframework.thread;

import org.junit.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import springframework.SpringContextHolder;

/**
 * spring-engine 2015/10/29 22:27
 * fuquanemail@gmail.com
 */
public class ThreadTest  {
    @Test
    public void t1(){
        ThreadPoolTaskExecutor taskExecutor =
                SpringContextHolder.getBean("threadPoolTaskExecutor");

        MyTask myTask1 = SpringContextHolder.getBean("myTask");
        MyTask myTask2 = SpringContextHolder.getBean("myTask");
        taskExecutor.execute(myTask1);
        taskExecutor.execute(myTask2);
    }
}
