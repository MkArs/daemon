package org.example;

import java.util.LinkedList;
import java.util.List;

public class TaskExecutor implements Runnable{
    private static final String EXECUTED_STR = " executed!";
    public static final List<TaskExecutor> THREAD_POOL = new LinkedList<>();
    public static final List<Task> TASK_POOL = new LinkedList<>();
    public List<Task> getQueue() {
        return queue;
    }

    private final List<Task> queue;

    public TaskExecutor(){
        this.queue = new LinkedList<>();
    }
    @Override
    public void run() {
        while (true){
            try {
                synchronized (this){
                    wait();
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (queue.size() == 0) continue;

            this.queue.get(0).getOuterMethod().run();
            Daemon.LOGGER.info(this.queue.get(0).getMethodName() + EXECUTED_STR);
            this.queue.remove(0);
        }
    }
}
