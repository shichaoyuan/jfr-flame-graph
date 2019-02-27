/*
 * Copyright 2017 Leonardo Freitas Gomes
 * Copyright 2018 Stefan Oehme
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

import com.beust.jcommander.IStringConverter;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.IType;
import org.openjdk.jmc.common.item.ItemToolkit;
import org.openjdk.jmc.common.unit.BinaryPrefix;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.common.util.MemberAccessorToolkit;
import org.openjdk.jmc.flightrecorder.JfrAttributes;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Different types of events possibly available in a JFR recording.
 * <p>
 * Each type can be activated using a command line option and can match one or many
 * JFR event types. Each type knows how to convert the event into a numeric value
 * that will make the flame graph most meaningful. For allocation events this would
 * be the number of bytes allocated, while for file reads it would be the duration of
 * the read operation.
 */
public enum EventType {

    METHOD_PROFILING_SAMPLE("cpu", ValueField.COUNT, "Method Profiling Sample"),
    ALLOCATION_IN_NEW_TLAB("allocation-tlab", ValueField.TLAB_SIZE, "Allocation in new TLAB"),
    ALLOCATION_OUTSIDE_TLAB("allocation-outside-tlab", ValueField.ALLOCATION_SIZE, "Allocation outside TLAB"),
    JAVA_EXCEPTION("exceptions", ValueField.COUNT, "Java Exception"),
    JAVA_MONITOR_BLOCKED("monitor-blocked", ValueField.DURATION, "Java Monitor Blocked"),
    IO("io", ValueField.DURATION, "File Read", "File Write", "Socket Read", "Socket Write");

    private final String commandLineOption;
    private final ValueField valueField;
    private final String[] eventNames;

    EventType(String commandLineOption, ValueField valueField, String... eventNames) {
        this.eventNames = eventNames;
        this.commandLineOption = commandLineOption;
        this.valueField = valueField;
    }

    public boolean matches(IItem event) {
        String name = event.getType().getName();
        return Arrays.stream(eventNames).anyMatch(name::equals);
    }

    public long getValue(IItem event) {
        return valueField.getValue(event);
    }

    @Override
    public String toString() {
        return commandLineOption;
    }


    public static final class EventTypeConverter implements IStringConverter<EventType> {
        private static final Map<String, EventType> typesByOption = new HashMap<>();

        static {
            for (EventType type : EventType.values()) {
                typesByOption.put(type.commandLineOption, type);
            }
        }

        @Override
        public EventType convert(String commandLineOption) {
            EventType eventType = typesByOption.get(commandLineOption);
            if (eventType == null) {
                throw new IllegalArgumentException("Event type [" + commandLineOption + "] does not exist.");
            }
            return eventType;
        }
    }

    private enum ValueField {
        COUNT {
            @Override
            public long getValue(IItem event) {
                return 1;
            }
        },
        DURATION {
            @Override
            public long getValue(IItem event) {
                IType<IItem> itemType = ItemToolkit.getItemType(event);
                IMemberAccessor<IQuantity, IItem> duration = itemType.getAccessor(JfrAttributes.DURATION.getKey());
                if (duration == null) {
                    IMemberAccessor<IQuantity, IItem> startTime = itemType.getAccessor(JfrAttributes.START_TIME.getKey());
                    IMemberAccessor<IQuantity, IItem> endTime = itemType.getAccessor(JfrAttributes.END_TIME.getKey());
                    duration = MemberAccessorToolkit.difference(endTime, startTime);
                }
                return duration.getMember(event).in(UnitLookup.MILLISECOND).longValue();
            }
        },
        ALLOCATION_SIZE {
            @Override
            public long getValue(IItem event) {
                IType<IItem> itemType = ItemToolkit.getItemType(event);
                IMemberAccessor<IQuantity, IItem> accessor = itemType.getAccessor(JdkAttributes.TLAB_SIZE.getKey());
                if (accessor == null) {
                    accessor = itemType.getAccessor(JdkAttributes.ALLOCATION_SIZE.getKey());
                }
                return accessor.getMember(event)
                        .in(UnitLookup.MEMORY.getUnit(BinaryPrefix.KIBI))
                        .longValue();
            }
        },
        TLAB_SIZE {
            @Override
            public long getValue(IItem event) {
                IType<IItem> itemType = ItemToolkit.getItemType(event);
                IMemberAccessor<IQuantity, IItem> accessor = itemType.getAccessor(JdkAttributes.TLAB_SIZE.getKey());
                if (accessor == null) {
                    accessor = itemType.getAccessor(JdkAttributes.TLAB_SIZE.getKey());
                }
                return accessor.getMember(event)
                        .in(UnitLookup.MEMORY.getUnit(BinaryPrefix.KIBI))
                        .longValue();
            }
        };

        public abstract long getValue(IItem event);
    }
}
