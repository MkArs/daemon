package org.example;

import com.cronutils.model.Cron;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import java.time.Duration;
import java.time.ZonedDateTime;

import static com.cronutils.model.CronType.QUARTZ;

public class Task {
    private final Runnable outerMethod;

    public String getJarFileName() {
        return jarFileName;
    }

    public String getClassName() {
        return className;
    }

    private final String jarFileName;
    private final String className;

    private final String methodName;
    private final String cronExpression;
    private volatile long timeBeforeExecution;
    private volatile boolean nextToExecute = false;
    public String getMethodName() {return methodName;}

    public Runnable getOuterMethod() {return outerMethod;}
    public Task(Runnable runnable, String jarFileName, String className, String cronExpression, String methodName) {
        this.outerMethod = runnable;
        this.jarFileName = jarFileName;
        this.className = className;
        this.methodName = methodName;
        cronExpression = cronExpression.substring(1, cronExpression.length() - 1); // Removing quotes
        this.timeBeforeExecution = cronToMillis(cronExpression);
        this.cronExpression = cronExpression;
    }

    public long cronToMillis(String cronExpression) {
        CronDefinition cronDefinition = CronDefinitionBuilder.instanceDefinitionFor(QUARTZ);
        CronParser parser = new CronParser(cronDefinition);
        Cron quartzCron = parser.parse(cronExpression);
        return getDuration(quartzCron);
    }

    public void cronToMillis() {
        CronDefinition cronDefinition = CronDefinitionBuilder.instanceDefinitionFor(QUARTZ);
        CronParser parser = new CronParser(cronDefinition);
        Cron quartzCron = parser.parse(this.cronExpression);

        this.timeBeforeExecution = getDuration(quartzCron);
    }

    private Long getDuration(Cron cron){
        ZonedDateTime now = ZonedDateTime.now();
        ExecutionTime executionTime = ExecutionTime.forCron(cron);
        ZonedDateTime nextExecution = executionTime.nextExecution(now).orElse(null);
        Duration duration = Duration.between(now, nextExecution);
        return duration.toMillis();
    }
    public long getTimeBeforeExecution() {
        return timeBeforeExecution;
    }

    public boolean getNextToExecute() {
        return nextToExecute;
    }

    public void setNextToExecute(boolean nextToExecute) {
        this.nextToExecute = nextToExecute;
    }
}
