/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.bluetooth.bluegiga.internal.command.gap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.bluetooth.bluegiga.internal.BlueGigaCommand;
import org.openhab.binding.bluetooth.bluegiga.internal.enumeration.GapDiscoverMode;

/**
 * Class to implement the BlueGiga command <b>discover</b>.
 * <p>
 * This command starts the GAP discovery procedure to scan for advertising devices i.e. to
 * perform a device discovery. Scanning parameters can be configured with the Set Scan
 * Parameters command before issuing this command. To cancel on an ongoing discovery process
 * use the End Procedure command.
 * <p>
 * This class provides methods for processing BlueGiga API commands.
 * <p>
 * Note that this code is autogenerated. Manual changes may be overwritten.
 *
 * @author Chris Jackson - Initial contribution of Java code generator
 */
@NonNullByDefault
public class BlueGigaDiscoverCommand extends BlueGigaCommand {
    public static int COMMAND_CLASS = 0x06;
    public static int COMMAND_METHOD = 0x02;

    private BlueGigaDiscoverCommand(CommandBuilder builder) {
        this.mode = builder.mode;
    }

    /**
     * see:GAP Discover Mode.
     * <p>
     * BlueGiga API type is <i>GapDiscoverMode</i> - Java type is {@link GapDiscoverMode}
     */
    private GapDiscoverMode mode;

    @Override
    public int[] serialize() {
        // Serialize the header
        serializeHeader(COMMAND_CLASS, COMMAND_METHOD);

        // Serialize the fields
        serializeGapDiscoverMode(mode);

        return getPayload();
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("BlueGigaDiscoverCommand [mode=");
        builder.append(mode);
        builder.append(']');
        return builder.toString();
    }

    public static class CommandBuilder {
        private GapDiscoverMode mode = GapDiscoverMode.UNKNOWN;

        /**
         * see:GAP Discover Mode.
         *
         * @param mode the mode to set as {@link GapDiscoverMode}
         */
        public CommandBuilder withMode(GapDiscoverMode mode) {
            this.mode = mode;
            return this;
        }

        public BlueGigaDiscoverCommand build() {
            return new BlueGigaDiscoverCommand(this);
        }
    }
}
