package org.quickload.exec;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import javax.validation.constraints.NotNull;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.fasterxml.jackson.databind.JsonNode;
import org.quickload.config.Task;
import org.quickload.config.Config;
import org.quickload.config.ConfigSource;
import org.quickload.config.TaskSource;
import org.quickload.config.NextConfig;
import org.quickload.config.Report;
import org.quickload.config.FailedReport;
import org.quickload.record.Schema;
import org.quickload.spi.ProcControl;
import org.quickload.spi.ProcTask;
import org.quickload.spi.ProcConfig;
import org.quickload.spi.InputPlugin;
import org.quickload.spi.OutputPlugin;
import org.quickload.spi.PluginThread;
import org.quickload.channel.PageChannel;

public class LocalExecutor
{
    private final Injector injector;
    private final ConfigSource systemConfig;

    public interface ExecutorTask
            extends Task
    {
        @Config("in")
        @NotNull
        public ConfigSource getInputConfig();

        // TODO
        @Config("out")
        @NotNull
        public ConfigSource getOutputConfig();

        public TaskSource getInputTask();
        public void setInputTask(TaskSource taskSource);

        public TaskSource getOutputTask();
        public void setOutputTask(TaskSource taskSource);
    }

    @Inject
    public LocalExecutor(Injector injector,
            @ForSystemConfig ConfigSource systemConfig)
    {
        this.injector = injector;
        this.systemConfig = systemConfig;
    }

    private static class ControlContext
    {
        private List<Report> inputReports;
        private List<Report> outputReports;
        private NextConfig inputNextConfig;
        private NextConfig outputNextConfig;

        public List<Report> getInputReports()
        {
            return inputReports;
        }

        public List<Report> getOutputReports()
        {
            return outputReports;
        }

        public void setInputReports(List<Report> inputReports)
        {
            this.inputReports = inputReports;
        }

        public void setOutputReports(List<Report> outputReports)
        {
            this.outputReports = outputReports;
        }

        public void setInputNextConfig(NextConfig inputNextConfig)
        {
            this.inputNextConfig = inputNextConfig;
        }

        public void setOutputNextConfig(NextConfig outputNextConfig)
        {
            this.outputNextConfig = outputNextConfig;
        }

        public NextConfig getInputNextConfig()
        {
            return inputNextConfig;
        }

        public NextConfig getOutputNextConfig()
        {
            return outputNextConfig;
        }
    }

    private static class ProcessContext
    {
        private final Report[] inputReports;
        private final Report[] outputReports;

        public ProcessContext(int processorCount)
        {
            this.inputReports = new Report[processorCount];
            this.outputReports = new Report[processorCount];
            for (int i=0; i < processorCount; i++) {
                inputReports[i] = outputReports[i] = new Report();
            }
        }

        public void setInputReport(int processorIndex, Report report)
        {
            inputReports[processorIndex] = report;
        }

        public void setOutputReport(int processorIndex, Report report)
        {
            outputReports[processorIndex] = report;
        }

        public List<Report> getInputReports()
        {
            return ImmutableList.copyOf(inputReports);
        }

        public List<Report> getOutputReports()
        {
            return ImmutableList.copyOf(outputReports);
        }
    }

    protected InputPlugin newInputPlugin(ProcTask proc, ExecutorTask task)
    {
        return proc.newPlugin(InputPlugin.class, task.getInputConfig().get("type"));
    }

    protected OutputPlugin newOutputPlugin(ProcTask proc, ExecutorTask task)
    {
        return proc.newPlugin(OutputPlugin.class, task.getOutputConfig().get("type"));
    }

    public NextConfig run(final ConfigSource config)
    {
        try {
            return doRun(config);
        } catch (Throwable ex) {
            throw PluginExecutors.propagePluginExceptions(ex);
        }
    }

    private NextConfig doRun(ConfigSource config)
    {
        final ProcTask proc = PluginExecutors.newProcTask(injector, config);
        final ExecutorTask task = proc.loadConfig(config, ExecutorTask.class);

        final InputPlugin in = newInputPlugin(proc, task);
        final OutputPlugin out = newOutputPlugin(proc, task);

        final ControlContext ctrlContext = new ControlContext();

        // TODO create and use ProcTaskBuilder to set default values

        NextConfig inputNextConfig = in.runInputTransaction(proc, task.getInputConfig(), new ProcControl() {
            public List<Report> run(final TaskSource inputTask)
            {
                NextConfig outputNextConfig = out.runOutputTransaction(proc, task.getOutputConfig(), new ProcControl() {
                    public List<Report> run(final TaskSource outputTask)
                    {
                        task.setInputTask(inputTask);
                        task.setOutputTask(outputTask);

                        // TODO debug log; use logger
                        System.out.println("input: "+task.getInputTask());
                        System.out.println("output: "+task.getOutputTask());

                        ProcessContext procContext = new ProcessContext(proc.getProcessorCount());

                        process(proc, proc.dumpTask(task), procContext);
                        ctrlContext.setOutputReports(procContext.getOutputReports());
                        ctrlContext.setInputReports(procContext.getInputReports());

                        return ctrlContext.getOutputReports();
                    }
                });
                ctrlContext.setOutputNextConfig(outputNextConfig);
                return ctrlContext.getInputReports();
            }
        });
        ctrlContext.setInputNextConfig(inputNextConfig);

        return ctrlContext.getInputNextConfig().setAll(ctrlContext.getOutputNextConfig());
    }

    private final void process(final ProcTask proc, final TaskSource taskSource, final ProcessContext procContext)
    {
        final ExecutorTask task = proc.loadTask(taskSource, ExecutorTask.class);
        final InputPlugin in = newInputPlugin(proc, task);
        final OutputPlugin out = newOutputPlugin(proc, task);

        // TODO multi-threading

        for (int i=0; i < proc.getProcessorCount(); i++) {
            final int processorIndex = i;

            PluginThread thread = null;
            Throwable error = null;
            try (final PageChannel channel = proc.newPageChannel()) {
                thread = proc.startPluginThread(new Runnable() {
                    public void run()
                    {
                        try {
                            // TODO return Report
                            Report report = out.runOutput(proc, task.getOutputTask(),
                                processorIndex, channel.getInput());
                            procContext.setOutputReport(processorIndex, report);
                        } catch (Throwable ex) {
                            procContext.setOutputReport(processorIndex, new FailedReport(ex));
                            throw ex;  // TODO error handling to propagate exceptions to runInputTransaction should be at the end of process() once multi-threaded
                        } finally {
                            channel.completeConsumer();
                        }
                    }
                });

                try {
                    Report report = in.runInput(proc, task.getInputTask(),
                            processorIndex, channel.getOutput());
                    channel.completeProducer();
                    thread.join();
                    channel.join();  // throws if channel is fully consumed
                    procContext.setInputReport(processorIndex, report);
                } catch (Throwable ex) {
                    procContext.setInputReport(processorIndex, new FailedReport(ex));
                    throw ex;  // TODO error handling to propagate exceptions to runInputTransactioat the end of process() once multi-threaded
                }

            } catch (Throwable ex) {
                error = ex;
            } finally {
                PluginThread.joinAndThrowNested(thread, error);
            }
        }
    }
}
