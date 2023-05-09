package org.example;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.exception.AllProcessorsBusyException;

import java.io.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.*;
import java.util.LinkedList;
import java.util.List;

public class Daemon {
    private static final String BUSY_EXCEPTION_MESSAGE = "All processors are busy! ";
    private static final String DAEMON_STOPPED_STR = "Daemon stopped! ";
    private static final String ERROR_STOPPING_DAEMON_STR = "Error stopping daemon! ";
    private static final int PORT = 1234;
    // If task's time before execution is within than that + waitTime,
    // the task is getting marked with setNextToExecute().
    // This is done due to possibility of task bypassing
    // its execution time.
    private static final int EXECUTION_GAP_MS = 200;
    private static final String REMOVE_STRING = "remove";
    private static final String FILE_PROTOCOL = "file:///";
    public static final Logger LOGGER = LogManager.getLogger(Daemon.class);
    private Socket clientSocket;
    private ServerSocket serverSocket;
    private volatile boolean areTasksReady = false;
    private BufferedReader input;
    private long waitTime = 0L;
    private Daemon() {}
    private static Runnable createRunnableFromMethod(Object targetClass, Method method) {
        return () -> {
            try {
                method.invoke(targetClass);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        };
    }
    private static void fillThreadPool() throws AllProcessorsBusyException {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int threadPoolSize = Math.max(1, availableProcessors - 1); // Leaving 1 core for system to operate

        if(availableProcessors == threadPoolSize){
            throw new AllProcessorsBusyException(BUSY_EXCEPTION_MESSAGE);
        }

        for(int i = 0; i < threadPoolSize; i++){
            TaskExecutor taskExecutor = new TaskExecutor();
            Thread thread = new Thread(taskExecutor);
            thread.start();
            TaskExecutor.THREAD_POOL.add(taskExecutor);
        }
    }
    private void start() {
        try {
            var taskPool = TaskExecutor.TASK_POOL;
            String inputLine;

            while (true) {
                try {
                    if(serverSocket == null){
                        serverSocket = new ServerSocket(PORT);
                        clientSocket = serverSocket.accept();
                        input = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    }
                    if((inputLine = input.readLine()) == null) continue;
                }catch (SocketException e){
                    serverSocket.close();
                    serverSocket = null;
                    continue;
                }

                int index = inputLine.indexOf(' ');
                if(index == -1){
                    continue;
                }

                String[] splitInputline = inputLine.split(" ", 4);
                if (splitInputline.length != 4) continue;
                String jarFileName = splitInputline[0];
                String className = splitInputline[1];
                String methodName = splitInputline[2];
                String timePeriod = splitInputline[3];

                if(jarFileName.equals(REMOVE_STRING)){
                    // Here and below there is a logical shift by 1 in indexes
                    // since jarFileName contains REMOVE_STRING
                    taskPool.removeIf(task ->
                        task.getJarFileName().equals(className) &&
                        task.getClassName().equals(methodName) &&
                        task.getMethodName().equals(timePeriod)
                    );
                    LOGGER.info(REMOVE_STRING + " " + className + " " + methodName + " " + timePeriod);
                }else {
                    Method method;
                    Object targetClass;
                    Class<?> clazz;
                    URLClassLoader classLoader;

                    try {
                        classLoader = new URLClassLoader(new URL[] { new URL(FILE_PROTOCOL + jarFileName) });
                        clazz = classLoader.loadClass(className);
                        method = clazz.getMethod(methodName);
                        targetClass = clazz.getDeclaredConstructor().newInstance();
                        classLoader.close();
                    } catch (NoSuchMethodException | InvocationTargetException | InstantiationException |
                             IllegalAccessException| ClassNotFoundException | MalformedURLException e) {
                        LOGGER.error(e.getMessage());
                        continue;
                    }

                    Runnable runnable = createRunnableFromMethod(targetClass, method);
                    try{
                        taskPool.add(new Task(runnable, jarFileName, className, timePeriod, methodName));
                    }catch (IllegalArgumentException e){
                        LOGGER.error(e.getMessage());
                        continue;
                    }
                }
                // Updating time before task execution
                updateTimeBeforeExecutions();
                synchronized (this){
                    waitTime = getWaitTime();
                    prepareTasks();
                    areTasksReady = false;
                    this.notifyAll();
                }
            }
        } catch (IOException e) {
            LOGGER.error(e.getMessage());
        } finally {
            stop();
        }
    }
    private void updateTimeBeforeExecutions(){
        var taskPool = TaskExecutor.TASK_POOL;

        for (var task :
                taskPool) {
            task.cronToMillis();
        }
    }
    private void sendTasks() {
        List<Task> urgentTasks = new LinkedList<>();
        var taskPool = TaskExecutor.TASK_POOL;
        var threadPool = TaskExecutor.THREAD_POOL;
        int taskCount;

        for (var task:
                taskPool) {
            if(task.getNextToExecute()){
                urgentTasks.add(task);
            }
        }

        taskCount = urgentTasks.size();

        while (taskCount != 0){
            for (var exec: threadPool) {
                taskCount--;
                exec.getQueue().add(urgentTasks.get(taskCount));
                synchronized(exec){
                    exec.notify();
                }
                if (taskCount == 0) break;
            }
        }
    }
    private long getWaitTime() {
        var pool = TaskExecutor.TASK_POOL;
        long waitTime = Long.MAX_VALUE;

        for (var task:
                pool) {
            if(task.getTimeBeforeExecution() < waitTime){
                waitTime = task.getTimeBeforeExecution();
            }
        }

        return waitTime;
    }
    private void stop() {
        try {
            if (clientSocket != null) clientSocket.close();
            LOGGER.info(DAEMON_STOPPED_STR);
        } catch (IOException e) {
            LOGGER.error(ERROR_STOPPING_DAEMON_STR + e.getMessage());
        }
    }
    private void createThreadNotifier() {
        Thread thread = new Thread(() ->{
            while (true){
                synchronized (this){
                    try {
                        if(TaskExecutor.TASK_POOL.size() == 0) continue;
                        this.wait(waitTime);
                        if(!areTasksReady){
                            areTasksReady = true;
                            continue;
                        }
                        sendTasks();
                        updateTimeBeforeExecutions();
                        waitTime = getWaitTime();
                        prepareTasks();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        });
        thread.start();
    }
    private void prepareTasks() {
        var taskPool = TaskExecutor.TASK_POOL;

        for (var task :
                taskPool) {
            task.setNextToExecute(
                    task.getTimeBeforeExecution() == waitTime ||
                    task.getTimeBeforeExecution() < waitTime + EXECUTION_GAP_MS
            );
        }
    }

    public static void main(String[] args) {
        Daemon daemon = new Daemon();

        try{
            daemon.createThreadNotifier();
            fillThreadPool();
        }catch (AllProcessorsBusyException e){
            LOGGER.error(e.getMessage());
            return;
        }

        daemon.start();
    }
}