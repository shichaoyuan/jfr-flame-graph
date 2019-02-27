/*
 * Copyright 2015 M. Isuru Tharanga Chrishantha Perera
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.chrishantha.jfr.flamegraph.output;

import com.beust.jcommander.Parameter;
import org.openjdk.jmc.common.IMCFrame;
import org.openjdk.jmc.common.IMCStackTrace;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.ItemToolkit;
import org.openjdk.jmc.flightrecorder.JfrAttributes;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;
import org.openjdk.jmc.flightrecorder.stacktrace.FrameSeparator;
import org.openjdk.jmc.flightrecorder.stacktrace.StacktraceFormatToolkit;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.Stack;
import java.util.zip.GZIPInputStream;

/**
 * Parse JFR dump and create a compatible output for Flame Graph
 */
public final class JFRToFlameGraphWriter {

    private final OutputWriterParameters parameters;
    @Parameter(names = {"-h", "--help"}, description = "Display Help", help = true)
    boolean help;

    @Parameter(names = {"-f", "--jfrdump"}, description = "Java Flight Recorder Dump", required = true)
    File jfrdump;

    @Parameter(names = {"-ot", "--output-type"}, description = "Output type")
    OutputType outputType = OutputType.FOLDED;

    @Parameter(names = {"-o", "--output"}, description = "Output file")
    File outputFile;

    @Parameter(names = {"-d", "--decompress"}, description = "Decompress the JFR file")
    boolean decompress;

    @Parameter(names = {"-i", "--ignore-line-numbers"}, description = "Ignore Line Numbers in Stack Frame")
    boolean ignoreLineNumbers;

    @Parameter(names = {"-rv", "--show-return-value"}, description = "Show return value for methods in the stack")
    boolean showReturnValue;

    @Parameter(names = {"-sn",
            "--use-simple-names"}, description = "Use simple names instead of qualified names in the stack")
    boolean useSimpleNames;

    @Parameter(names = {"-ha", "--hide-arguments"}, description = "Hide arguments in methods")
    boolean hideArguments;

    @Parameter(names = {"-j", "--print-jfr-details"}, description = "Print JFR details and exit")
    boolean printJFRDetails;

    @Parameter(names = {"-t", "--print-timestamp"}, description = "Print timestamp in JFR Details")
    boolean printTimestamp;

    @Parameter(names = {"-st", "--start-timestamp"}, description = "Start timestamp in seconds for filtering", converter = SecondsToNanosConverter.class)
    long startTimestamp = Long.MIN_VALUE;

    @Parameter(names = {"-et", "--end-timestamp"}, description = "End timestamp in seconds for filtering", converter = SecondsToNanosConverter.class)
    long endTimestamp = Long.MAX_VALUE;

    @Parameter(names = {"-e",
            "--event"}, description = "Type of event used to generate the flamegraph", converter = EventType.EventTypeConverter.class)
    EventType eventType = EventType.METHOD_PROFILING_SAMPLE;

    private static final String EVENT_VALUE_STACK = "(stackTrace)";

    private static final String PRINT_FORMAT = "%-16s: %s%n";

    private static final String DURATION_FORMAT = "{0} h {1} min";

    public JFRToFlameGraphWriter(OutputWriterParameters parameters) {
        this.parameters = parameters;
    }

    public void process() throws Exception {
        IItemCollection recording = loadRecording();

        if (printJFRDetails) {
            printJFRDetails(recording);
        } else {
            convertToStacks(recording);
        }
    }

    private IItemCollection loadRecording() throws Exception {
        IItemCollection recording;
        try {
            recording = JfrLoaderToolkit.loadEvents(decompress ? decompressFile(jfrdump) : jfrdump);
        } catch (Exception e) {
            System.err.println("Could not load the JFR file.");
            if (!decompress) {
                System.err.println("If the JFR file is compressed, try the decompress option");
            }
            throw e;
        }
        return recording;
    }

    private void convertToStacks(IItemCollection recording) throws IOException {
        FlameGraphOutputWriter flameGraphOutputWriter = outputType.createFlameGraphOutputWriter();
        flameGraphOutputWriter.initialize(parameters);


        for (IItemIterable items : recording) {
            for (IItem item : items) {
                if (!eventType.matches(item)) {
                    continue;
                }

                IMCStackTrace stackTrace = getStackTrace(item);
                if (stackTrace == null) {
                    continue;
                }

                Stack<String> stack = getStack(stackTrace);
                long value = eventType.getValue(item);
                flameGraphOutputWriter.processEvent(0, 0, 0, stack, value);
            }
        }

        try (Writer writer = outputFile != null ? new FileWriter(outputFile) : new PrintWriter(System.out);
             BufferedWriter bufferedWriter = new BufferedWriter(writer)) {
            flameGraphOutputWriter.writeOutput(bufferedWriter);
        }
    }

    private static IMCStackTrace getStackTrace(IItem event) {
        return ItemToolkit.getItemType(event).getAccessor(JfrAttributes.EVENT_STACKTRACE.getKey()).getMember(event);
    }

    /*
    private boolean matchesTimeRange(IEvent event) {
        long eventStartTimestamp = event.getStartTimestamp();
        long eventEndTimestamp = event.getEndTimestamp();
        if (eventStartTimestamp >= startTimestamp && eventStartTimestamp <= endTimestamp) {
            return true;
        } else if (eventEndTimestamp >= startTimestamp && eventEndTimestamp <= endTimestamp) {
            return true;
        }
        return false;
    }
    */

    private void printJFRDetails(IItemCollection recording) {
        /*ITimeRange timeRange = recording.getTimeRange();

        long startTimestamp = TimeUnit.NANOSECONDS.toSeconds(timeRange.getStartTimestamp());
        long endTimestamp = TimeUnit.NANOSECONDS.toSeconds(timeRange.getEndTimestamp());

        Duration d = Duration.ofNanos(timeRange.getDuration());
        long hours = d.toHours();
        long minutes = d.minusHours(hours).toMinutes();

        IView view = recording.createView();

        long minEventStartTimestamp = Long.MAX_VALUE;
        long maxEventEndTimestamp = 0;

        view.setFilter(eventType::matches);

        for (IEvent event : view) {
            long eventStartTimestamp = event.getStartTimestamp();
            long eventEndTimestamp = event.getEndTimestamp();
            if (eventStartTimestamp < minEventStartTimestamp) {
                minEventStartTimestamp = eventStartTimestamp;
            }

            if (eventEndTimestamp > maxEventEndTimestamp) {
                maxEventEndTimestamp = eventEndTimestamp;
            }
        }

        Duration eventsDuration = Duration.ofNanos(maxEventEndTimestamp - minEventStartTimestamp);
        long eventHours = eventsDuration.toHours();
        long eventMinutes = eventsDuration.minusHours(eventHours).toMinutes();

        minEventStartTimestamp = TimeUnit.NANOSECONDS.toSeconds(minEventStartTimestamp);
        maxEventEndTimestamp = TimeUnit.NANOSECONDS.toSeconds(maxEventEndTimestamp);

        System.out.println("JFR Details");
        if (printTimestamp) {
            System.out.format(PRINT_FORMAT, "Start", startTimestamp);
            System.out.format(PRINT_FORMAT, "End", endTimestamp);
            System.out.format(PRINT_FORMAT, "Min Start Event", minEventStartTimestamp);
            System.out.format(PRINT_FORMAT, "Max End Event", maxEventEndTimestamp);
        } else {
            Instant startInstant = Instant.ofEpochSecond(startTimestamp);
            Instant endInstant = Instant.ofEpochSecond(endTimestamp);
            Instant minStartInstant = Instant.ofEpochSecond(minEventStartTimestamp);
            Instant maxEndInstant = Instant.ofEpochSecond(maxEventEndTimestamp);
            DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG)
                    .withZone(ZoneId.systemDefault());
            System.out.format(PRINT_FORMAT, "Start", formatter.format(startInstant));
            System.out.format(PRINT_FORMAT, "End", formatter.format(endInstant));
            System.out.format(PRINT_FORMAT, "Min Start Event", formatter.format(minStartInstant));
            System.out.format(PRINT_FORMAT, "Max End Event", formatter.format(maxEndInstant));
        }
        System.out.format(PRINT_FORMAT, "JFR Duration", MessageFormat.format(DURATION_FORMAT, hours, minutes));
        System.out.format(PRINT_FORMAT, "Events Duration",
                MessageFormat.format(DURATION_FORMAT, eventHours, eventMinutes));*/
        System.out.println("not implemented yet");
    }

    private Stack<String> getStack(IMCStackTrace stackTrace) {
        Stack<String> stack = new Stack<>();
        for (IMCFrame frame : stackTrace.getFrames()) {
            String frameName = getFrameName(frame);
            if (frameName != null) {
                stack.push(frameName);
            }
        }
        return stack;
    }

    private String getFrameName(IMCFrame frame) {

        return StacktraceFormatToolkit.formatFrame(
                frame,
                new FrameSeparator(!ignoreLineNumbers ? FrameSeparator.FrameCategorization.LINE : FrameSeparator.FrameCategorization.METHOD, false),
                false,
                false,
                true,
                true,
                false,
                true
        );
    }

    private File decompressFile(final File compressedFile) throws IOException {
        byte[] buffer = new byte[8 * 1024];

        File decompressedFile;

        try (GZIPInputStream compressedStream = new GZIPInputStream(new FileInputStream(compressedFile));
             FileOutputStream uncompressedFileStream = new FileOutputStream(
                     decompressedFile = File.createTempFile("jfr_", null))) {

            decompressedFile.deleteOnExit();
            int numberOfBytes;

            while ((numberOfBytes = compressedStream.read(buffer)) > 0) {
                uncompressedFileStream.write(buffer, 0, numberOfBytes);
            }
        }

        return decompressedFile;
    }

}
