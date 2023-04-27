package org.embulk.spi.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.embulk.config.ConfigSource;
import org.embulk.config.TaskSource;
import org.embulk.plugin.PluginType;
import org.embulk.spi.DecoderPlugin;
import org.embulk.spi.ExecSessionInternal;
import org.embulk.spi.FileInput;

public abstract class DecodersInternal {
    private DecodersInternal() {}

    public static List<DecoderPlugin> newDecoderPlugins(ExecSessionInternal execInternal, List<ConfigSource> configs) {
        final ArrayList<DecoderPlugin> builder = new ArrayList<>();
        for (ConfigSource config : configs) {
            builder.add(execInternal.newPlugin(DecoderPlugin.class, config.get(PluginType.class, "type")));
        }
        return Collections.unmodifiableList(builder);
    }

    public interface Control {
        public void run(List<TaskSource> taskSources);
    }

    public static void transaction(List<DecoderPlugin> plugins, List<ConfigSource> configs, DecodersInternal.Control control) {
        new RecursiveControl(plugins, configs, control).transaction();
    }

    public static FileInputDecoderOpen open(List<DecoderPlugin> plugins, List<TaskSource> taskSources, FileInput input) {
        FileInput in = input;
        List<FileInput> decoder = new ArrayList<>();
        int pos = 0;
        while (pos < plugins.size()) {
            in = plugins.get(pos).open(taskSources.get(pos), in);
            decoder.add(in);
            pos++;
        }
        return new FileInputDecoderOpen(in, decoder);
    }
    
    public static class FileInputDecoderOpen {
        final FileInput in;
        final List<FileInput> decoder;

        public FileInputDecoderOpen(FileInput in, List<FileInput> decoder) {
            this.in = in;
            this.decoder = decoder;
        }

        public FileInput getIn() {
            return in;
        }
        
        public List<FileInput> getDecoder() {
            return decoder;
        }
    }

    private static class RecursiveControl {
        private final List<DecoderPlugin> plugins;
        private final List<ConfigSource> configs;
        private final DecodersInternal.Control finalControl;
        private final ArrayList<TaskSource> taskSources;
        private int pos;

        RecursiveControl(List<DecoderPlugin> plugins, List<ConfigSource> configs,
                DecodersInternal.Control finalControl) {
            this.plugins = plugins;
            this.configs = configs;
            this.finalControl = finalControl;
            this.taskSources = new ArrayList<>();
        }

        public void transaction() {
            if (pos < plugins.size()) {
                plugins.get(pos).transaction(configs.get(pos), new DecoderPlugin.Control() {
                        public void run(TaskSource taskSource) {
                            taskSources.add(taskSource);
                            pos++;
                            transaction();
                        }
                    });
            } else {
                finalControl.run(Collections.unmodifiableList(this.taskSources));
            }
        }
    }
}
