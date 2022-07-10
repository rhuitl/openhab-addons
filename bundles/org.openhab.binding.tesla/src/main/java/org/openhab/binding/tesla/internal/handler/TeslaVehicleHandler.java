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
package org.openhab.binding.tesla.internal.handler;

import static org.openhab.binding.tesla.internal.TeslaBindingConstants.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.measure.quantity.Temperature;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.tesla.internal.TeslaBindingConstants;
import org.openhab.binding.tesla.internal.TeslaChannelSelectorProxy;
import org.openhab.binding.tesla.internal.TeslaChannelSelectorProxy.TeslaChannelSelector;
import org.openhab.binding.tesla.internal.handler.TeslaAccountHandler.Request;
import org.openhab.binding.tesla.internal.protocol.ChargeState;
import org.openhab.binding.tesla.internal.protocol.ClimateState;
import org.openhab.binding.tesla.internal.protocol.DriveState;
import org.openhab.binding.tesla.internal.protocol.GUIState;
import org.openhab.binding.tesla.internal.protocol.Vehicle;
import org.openhab.binding.tesla.internal.protocol.VehicleState;
import org.openhab.binding.tesla.internal.throttler.QueueChannelThrottler;
import org.openhab.binding.tesla.internal.throttler.Rate;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.IncreaseDecreaseType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The {@link TeslaVehicleHandler} is responsible for handling commands, which are sent
 * to one of the channels of a specific vehicle.
 *
 * @author Karel Goderis - Initial contribution
 * @author Kai Kreuzer - Refactored to use separate account handler and improved configuration options
 */
public class TeslaVehicleHandler extends BaseThingHandler {

    private static final int FAST_STATUS_REFRESH_INTERVAL = 15000;
    private static final int SLOW_STATUS_REFRESH_INTERVAL = 60000;
    private static final int API_SLEEP_INTERVAL_MINUTES = 20;
    private static final int MOVE_THRESHOLD_INTERVAL_MINUTES = 5;

    private final Logger logger = LoggerFactory.getLogger(TeslaVehicleHandler.class);

    protected WebTarget eventTarget;

    // Vehicle state variables
    protected Vehicle vehicle;
    protected String vehicleJSON;
    protected DriveState driveState;
    protected GUIState guiState;
    protected VehicleState vehicleState;
    protected ChargeState chargeState;
    protected ClimateState climateState;

    protected boolean allowWakeUp;
    protected boolean allowWakeUpForCommands;
    protected long lastTimeStamp;
    protected long apiIntervalTimestamp;
    protected int apiIntervalErrors;
    protected long eventIntervalTimestamp;
    protected int eventIntervalErrors;
    protected ReentrantLock lock;

    protected double lastLongitude;
    protected double lastLatitude;
    protected long lastLocationChangeTimestamp;

    protected long lastStateTimestamp = System.currentTimeMillis();
    protected String lastState = "";
    protected boolean isInactive = false;

    protected TeslaAccountHandler account;

    protected QueueChannelThrottler stateThrottler;
    protected ClientBuilder clientBuilder;
    protected Client eventClient;
    protected TeslaChannelSelectorProxy teslaChannelSelectorProxy = new TeslaChannelSelectorProxy();
    protected Thread eventThread;
    protected ScheduledFuture<?> fastStateJob;
    protected ScheduledFuture<?> slowStateJob;

    private final Gson gson = new Gson();

    public TeslaVehicleHandler(Thing thing, ClientBuilder clientBuilder) {
        super(thing);
        this.clientBuilder = clientBuilder;
    }

    @SuppressWarnings("null")
    @Override
    public void initialize() {
        logger.trace("Initializing the Tesla handler for {}", getThing().getUID());
        updateStatus(ThingStatus.UNKNOWN);
        allowWakeUp = (boolean) getConfig().get(TeslaBindingConstants.CONFIG_ALLOWWAKEUP);
        allowWakeUpForCommands = (boolean) getConfig().get(TeslaBindingConstants.CONFIG_ALLOWWAKEUPFORCOMMANDS);

        // the streaming API seems to be broken - let's keep the code, if it comes back one day
        // enableEvents = (boolean) getConfig().get(TeslaBindingConstants.CONFIG_ENABLEEVENTS);

        account = (TeslaAccountHandler) getBridge().getHandler();
        lock = new ReentrantLock();
        scheduler.execute(() -> queryVehicleAndUpdate());

        lock.lock();
        try {
            Map<Object, Rate> channels = new HashMap<>();
            channels.put(DATA_THROTTLE, new Rate(1, 1, TimeUnit.SECONDS));
            channels.put(COMMAND_THROTTLE, new Rate(20, 1, TimeUnit.MINUTES));

            Rate firstRate = new Rate(20, 1, TimeUnit.MINUTES);
            Rate secondRate = new Rate(200, 10, TimeUnit.MINUTES);
            stateThrottler = new QueueChannelThrottler(firstRate, scheduler, channels);
            stateThrottler.addRate(secondRate);

            if (fastStateJob == null || fastStateJob.isCancelled()) {
                fastStateJob = scheduler.scheduleWithFixedDelay(fastStateRunnable, 0, FAST_STATUS_REFRESH_INTERVAL,
                        TimeUnit.MILLISECONDS);
            }

            if (slowStateJob == null || slowStateJob.isCancelled()) {
                slowStateJob = scheduler.scheduleWithFixedDelay(slowStateRunnable, 0, SLOW_STATUS_REFRESH_INTERVAL,
                        TimeUnit.MILLISECONDS);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void dispose() {
        logger.trace("Disposing the Tesla handler for {}", getThing().getUID());
        lock.lock();
        try {
            if (fastStateJob != null && !fastStateJob.isCancelled()) {
                fastStateJob.cancel(true);
                fastStateJob = null;
            }

            if (slowStateJob != null && !slowStateJob.isCancelled()) {
                slowStateJob.cancel(true);
                slowStateJob = null;
            }

            if (eventThread != null && !eventThread.isInterrupted()) {
                eventThread.interrupt();
                eventThread = null;
            }
        } finally {
            lock.unlock();
        }

        if (eventClient != null) {
            eventClient.close();
        }
    }

    /**
     * Retrieves the unique vehicle id this handler is associated with
     *
     * @return the vehicle id
     */
    public String getVehicleId() {
        if (vehicle != null) {
            return vehicle.id;
        } else {
            return null;
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("handleCommand {} {}", channelUID, command);
        String channelID = channelUID.getId();
        TeslaChannelSelector selector = TeslaChannelSelector.getValueSelectorFromChannelID(channelID);

        if (command instanceof RefreshType) {
            if (!isAwake()) {
                logger.debug("Waking vehicle to refresh all data");
                wakeUp();
            }

            setActive();

            // Request the state of all known variables. This is sub-optimal, but the requests get scheduled and
            // throttled so we are safe not to break the Tesla SLA
            requestAllData();
        } else {
            if (selector != null) {
                if (!isAwake() && allowWakeUpForCommands) {
                    logger.debug("Waking vehicle to send command.");
                    wakeUp();
                    setActive();
                }
                try {
                    switch (selector) {
                        case CHARGE_LIMIT_SOC: {
                            if (command instanceof PercentType) {
                                setChargeLimit(((PercentType) command).intValue());
                            } else if (command instanceof OnOffType && command == OnOffType.ON) {
                                setChargeLimit(100);
                            } else if (command instanceof OnOffType && command == OnOffType.OFF) {
                                setChargeLimit(0);
                            } else if (command instanceof IncreaseDecreaseType
                                    && command == IncreaseDecreaseType.INCREASE) {
                                setChargeLimit(Math.min(chargeState.charge_limit_soc + 1, 100));
                            } else if (command instanceof IncreaseDecreaseType
                                    && command == IncreaseDecreaseType.DECREASE) {
                                setChargeLimit(Math.max(chargeState.charge_limit_soc - 1, 0));
                            }
                            break;
                        }
                        case CHARGE_AMPS:
                            Integer amps = null;
                            if (command instanceof DecimalType) {
                                amps = ((DecimalType) command).intValue();
                            }
                            if (command instanceof QuantityType<?>) {
                                QuantityType<?> qamps = ((QuantityType<?>) command).toUnit(Units.AMPERE);
                                if (qamps != null) {
                                    amps = qamps.intValue();
                                }
                            }
                            if (amps != null) {
                                if (amps < 5 || amps > 32) {
                                    logger.warn("Charging amps can only be set in a range of 5-32A, but not to {}A.",
                                            amps);
                                    return;
                                }
                                setChargingAmps(amps);
                            }
                            break;
                        case COMBINED_TEMP: {
                            QuantityType<Temperature> quantity = commandToQuantityType(command);
                            if (quantity != null) {
                                setCombinedTemperature(quanityToRoundedFloat(quantity));
                            }
                            break;
                        }
                        case DRIVER_TEMP: {
                            QuantityType<Temperature> quantity = commandToQuantityType(command);
                            if (quantity != null) {
                                setDriverTemperature(quanityToRoundedFloat(quantity));
                            }
                            break;
                        }
                        case PASSENGER_TEMP: {
                            QuantityType<Temperature> quantity = commandToQuantityType(command);
                            if (quantity != null) {
                                setPassengerTemperature(quanityToRoundedFloat(quantity));
                            }
                            break;
                        }
                        case SENTRY_MODE: {
                            if (command instanceof OnOffType) {
                                setSentryMode(command == OnOffType.ON);
                            }
                            break;
                        }
                        case SUN_ROOF_STATE: {
                            if (command instanceof StringType) {
                                setSunroof(command.toString());
                            }
                            break;
                        }
                        case CHARGE_TO_MAX: {
                            if (command instanceof OnOffType) {
                                if (((OnOffType) command) == OnOffType.ON) {
                                    setMaxRangeCharging(true);
                                } else {
                                    setMaxRangeCharging(false);
                                }
                            }
                            break;
                        }
                        case CHARGE: {
                            if (command instanceof OnOffType) {
                                if (((OnOffType) command) == OnOffType.ON) {
                                    charge(true);
                                } else {
                                    charge(false);
                                }
                            }
                            break;
                        }
                        case FLASH: {
                            if (command instanceof OnOffType) {
                                if (((OnOffType) command) == OnOffType.ON) {
                                    flashLights();
                                }
                            }
                            break;
                        }
                        case HONK_HORN: {
                            if (command instanceof OnOffType) {
                                if (((OnOffType) command) == OnOffType.ON) {
                                    honkHorn();
                                }
                            }
                            break;
                        }
                        case CHARGEPORT: {
                            if (command instanceof OnOffType) {
                                if (((OnOffType) command) == OnOffType.ON) {
                                    openChargePort();
                                }
                            }
                            break;
                        }
                        case DOOR_LOCK: {
                            if (command instanceof OnOffType) {
                                if (((OnOffType) command) == OnOffType.ON) {
                                    lockDoors(true);
                                } else {
                                    lockDoors(false);
                                }
                            }
                            break;
                        }
                        case AUTO_COND: {
                            if (command instanceof OnOffType) {
                                if (((OnOffType) command) == OnOffType.ON) {
                                    autoConditioning(true);
                                } else {
                                    autoConditioning(false);
                                }
                            }
                            break;
                        }
                        case WAKEUP: {
                            if (command instanceof OnOffType) {
                                if (((OnOffType) command) == OnOffType.ON) {
                                    wakeUp();
                                }
                            }
                            break;
                        }
                        case FT: {
                            if (command instanceof OnOffType) {
                                if (((OnOffType) command) == OnOffType.ON) {
                                    openFrunk();
                                }
                            }
                            break;
                        }
                        case RT: {
                            if (command instanceof OnOffType) {
                                if (((OnOffType) command) == OnOffType.ON) {
                                    if (vehicleState.rt == 0) {
                                        openTrunk();
                                    }
                                } else {
                                    if (vehicleState.rt == 1) {
                                        closeTrunk();
                                    }
                                }
                            }
                            break;
                        }
                        case VALET_MODE: {
                            if (command instanceof OnOffType) {
                                int valetpin = ((BigDecimal) getConfig().get(VALETPIN)).intValue();
                                if (((OnOffType) command) == OnOffType.ON) {
                                    setValetMode(true, valetpin);
                                } else {
                                    setValetMode(false, valetpin);
                                }
                            }
                            break;
                        }
                        case RESET_VALET_PIN: {
                            if (command instanceof OnOffType) {
                                if (((OnOffType) command) == OnOffType.ON) {
                                    resetValetPin();
                                }
                            }
                            break;
                        }
                        default:
                            break;
                    }
                    return;
                } catch (IllegalArgumentException e) {
                    logger.warn(
                            "An error occurred while trying to set the read-only variable associated with channel '{}' to '{}'",
                            channelID, command.toString());
                }
            }
        }
    }

    public void sendCommand(String command, String payLoad, WebTarget target) {
        if (command.equals(COMMAND_WAKE_UP) || isAwake() || allowWakeUpForCommands) {
            Request request = account.newRequest(this, command, payLoad, target, allowWakeUpForCommands);
            if (stateThrottler != null) {
                stateThrottler.submit(COMMAND_THROTTLE, request);
            }
        }
    }

    public void sendCommand(String command) {
        sendCommand(command, "{}");
    }

    public void sendCommand(String command, String payLoad) {
        if (command.equals(COMMAND_WAKE_UP) || isAwake() || allowWakeUpForCommands) {
            Request request = account.newRequest(this, command, payLoad, account.commandTarget, allowWakeUpForCommands);
            if (stateThrottler != null) {
                stateThrottler.submit(COMMAND_THROTTLE, request);
            }
        }
    }

    public void sendCommand(String command, WebTarget target) {
        if (command.equals(COMMAND_WAKE_UP) || isAwake() || allowWakeUpForCommands) {
            Request request = account.newRequest(this, command, "{}", target, allowWakeUpForCommands);
            if (stateThrottler != null) {
                stateThrottler.submit(COMMAND_THROTTLE, request);
            }
        }
    }

    public void requestData(String command, String payLoad) {
        if (command.equals(COMMAND_WAKE_UP) || isAwake() || allowWakeUpForCommands) {
            Request request = account.newRequest(this, command, payLoad, account.dataRequestTarget, false);
            if (stateThrottler != null) {
                stateThrottler.submit(DATA_THROTTLE, request);
            }
        }
    }

    @Override
    protected void updateStatus(ThingStatus status) {
        super.updateStatus(status);
    }

    @Override
    protected void updateStatus(ThingStatus status, ThingStatusDetail statusDetail) {
        super.updateStatus(status, statusDetail);
    }

    @Override
    protected void updateStatus(ThingStatus status, ThingStatusDetail statusDetail, @Nullable String description) {
        super.updateStatus(status, statusDetail, description);
    }

    public void requestData(String command) {
        requestData(command, null);
    }

    public void queryVehicle(String parameter) {
        WebTarget target = account.vehicleTarget.path(parameter);
        sendCommand(parameter, null, target);
    }

    public void requestAllData() {
        requestData(DRIVE_STATE);
        requestData(VEHICLE_STATE);
        requestData(CHARGE_STATE);
        requestData(CLIMATE_STATE);
        requestData(GUI_STATE);
    }

    protected boolean isAwake() {
        return vehicle != null && "online".equals(vehicle.state) && vehicle.vehicle_id != null;
    }

    protected boolean isInMotion() {
        if (driveState != null) {
            if (driveState.speed != null && driveState.shift_state != null) {
                return !"Undefined".equals(driveState.speed)
                        && (!"P".equals(driveState.shift_state) || !"Undefined".equals(driveState.shift_state));
            }
        }
        return false;
    }

    protected boolean isInactive() {
        // vehicle is inactive in case
        // - it does not charge
        // - it has not moved in the observation period
        return isInactive && !isCharging() && !hasMovedInSleepInterval();
    }

    protected boolean isCharging() {
        return chargeState != null && "Charging".equals(chargeState.charging_state);
    }

    protected boolean hasMovedInSleepInterval() {
        return lastLocationChangeTimestamp > (System.currentTimeMillis()
                - (MOVE_THRESHOLD_INTERVAL_MINUTES * 60 * 1000));
    }

    protected boolean allowQuery() {
        return (isAwake() && !isInactive());
    }

    protected void setActive() {
        isInactive = false;
        lastLocationChangeTimestamp = System.currentTimeMillis();
        lastLatitude = 0;
        lastLongitude = 0;
    }

    protected boolean checkResponse(Response response, boolean immediatelyFail) {
        if (response != null && response.getStatus() == 200) {
            return true;
        } else if (response != null && response.getStatus() == 401) {
            logger.debug("The access token has expired, trying to get a new one.");
            account.authenticate();
            return false;
        } else {
            apiIntervalErrors++;
            if (immediatelyFail || apiIntervalErrors >= TeslaAccountHandler.API_MAXIMUM_ERRORS_IN_INTERVAL) {
                if (immediatelyFail) {
                    logger.warn("Got an unsuccessful result, setting vehicle to offline and will try again");
                } else {
                    logger.warn("Reached the maximum number of errors ({}) for the current interval ({} seconds)",
                            TeslaAccountHandler.API_MAXIMUM_ERRORS_IN_INTERVAL,
                            TeslaAccountHandler.API_ERROR_INTERVAL_SECONDS);
                }

                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR);
                if (eventClient != null) {
                    eventClient.close();
                }
            } else if ((System.currentTimeMillis() - apiIntervalTimestamp) > 1000
                    * TeslaAccountHandler.API_ERROR_INTERVAL_SECONDS) {
                logger.trace("Resetting the error counter. ({} errors in the last interval)", apiIntervalErrors);
                apiIntervalTimestamp = System.currentTimeMillis();
                apiIntervalErrors = 0;
            }
        }

        return false;
    }

    public void setChargeLimit(int percent) {
        JsonObject payloadObject = new JsonObject();
        payloadObject.addProperty("percent", percent);
        sendCommand(COMMAND_SET_CHARGE_LIMIT, gson.toJson(payloadObject), account.commandTarget);
        requestData(CHARGE_STATE);
    }

    public void setChargingAmps(int amps) {
        JsonObject payloadObject = new JsonObject();
        payloadObject.addProperty("charging_amps", amps);
        sendCommand(COMMAND_SET_CHARGING_AMPS, gson.toJson(payloadObject), account.commandTarget);
        requestData(CHARGE_STATE);
    }

    public void setSentryMode(boolean b) {
        JsonObject payloadObject = new JsonObject();
        payloadObject.addProperty("on", b);
        sendCommand(COMMAND_SET_SENTRY_MODE, gson.toJson(payloadObject), account.commandTarget);
        requestData(VEHICLE_STATE);
    }

    public void setSunroof(String state) {
        if (state.equals("vent") || state.equals("close")) {
            JsonObject payloadObject = new JsonObject();
            payloadObject.addProperty("state", state);
            sendCommand(COMMAND_SUN_ROOF, gson.toJson(payloadObject), account.commandTarget);
            requestData(VEHICLE_STATE);
        } else {
            logger.warn("Ignoring invalid command '{}' for sunroof.", state);
        }
    }

    /**
     * Sets the driver and passenger temperatures.
     *
     * While setting different temperature values is supported by the API, in practice this does not always work
     * reliably, possibly if the the
     * only reliable method is to set the driver and passenger temperature to the same value
     *
     * @param driverTemperature in Celsius
     * @param passenegerTemperature in Celsius
     */
    public void setTemperature(float driverTemperature, float passenegerTemperature) {
        JsonObject payloadObject = new JsonObject();
        payloadObject.addProperty("driver_temp", driverTemperature);
        payloadObject.addProperty("passenger_temp", passenegerTemperature);
        sendCommand(COMMAND_SET_TEMP, gson.toJson(payloadObject), account.commandTarget);
        requestData(CLIMATE_STATE);
    }

    public void setCombinedTemperature(float temperature) {
        setTemperature(temperature, temperature);
    }

    public void setDriverTemperature(float temperature) {
        setTemperature(temperature, climateState != null ? climateState.passenger_temp_setting : temperature);
    }

    public void setPassengerTemperature(float temperature) {
        setTemperature(climateState != null ? climateState.driver_temp_setting : temperature, temperature);
    }

    public void openFrunk() {
        JsonObject payloadObject = new JsonObject();
        payloadObject.addProperty("which_trunk", "front");
        sendCommand(COMMAND_ACTUATE_TRUNK, gson.toJson(payloadObject), account.commandTarget);
        requestData(VEHICLE_STATE);
    }

    public void openTrunk() {
        JsonObject payloadObject = new JsonObject();
        payloadObject.addProperty("which_trunk", "rear");
        sendCommand(COMMAND_ACTUATE_TRUNK, gson.toJson(payloadObject), account.commandTarget);
        requestData(VEHICLE_STATE);
    }

    public void closeTrunk() {
        openTrunk();
    }

    public void setValetMode(boolean b, Integer pin) {
        JsonObject payloadObject = new JsonObject();
        payloadObject.addProperty("on", b);
        if (pin != null) {
            payloadObject.addProperty("password", String.format("%04d", pin));
        }
        sendCommand(COMMAND_SET_VALET_MODE, gson.toJson(payloadObject), account.commandTarget);
        requestData(VEHICLE_STATE);
    }

    public void resetValetPin() {
        sendCommand(COMMAND_RESET_VALET_PIN, account.commandTarget);
        requestData(VEHICLE_STATE);
    }

    public void setMaxRangeCharging(boolean b) {
        sendCommand(b ? COMMAND_CHARGE_MAX : COMMAND_CHARGE_STD, account.commandTarget);
        requestData(CHARGE_STATE);
    }

    public void charge(boolean b) {
        sendCommand(b ? COMMAND_CHARGE_START : COMMAND_CHARGE_STOP, account.commandTarget);
        requestData(CHARGE_STATE);
    }

    public void flashLights() {
        sendCommand(COMMAND_FLASH_LIGHTS, account.commandTarget);
    }

    public void honkHorn() {
        sendCommand(COMMAND_HONK_HORN, account.commandTarget);
    }

    public void openChargePort() {
        sendCommand(COMMAND_OPEN_CHARGE_PORT, account.commandTarget);
        requestData(CHARGE_STATE);
    }

    public void lockDoors(boolean b) {
        sendCommand(b ? COMMAND_DOOR_LOCK : COMMAND_DOOR_UNLOCK, account.commandTarget);
        requestData(VEHICLE_STATE);
    }

    public void autoConditioning(boolean b) {
        sendCommand(b ? COMMAND_AUTO_COND_START : COMMAND_AUTO_COND_STOP, account.commandTarget);
        requestData(CLIMATE_STATE);
    }

    public void wakeUp() {
        sendCommand(COMMAND_WAKE_UP, account.wakeUpTarget);
    }

    protected Vehicle queryVehicle() {
        String authHeader = account.getAuthHeader();

        if (authHeader != null) {
            try {
                // get a list of vehicles
                Response response = account.vehiclesTarget.request(MediaType.APPLICATION_JSON_TYPE)
                        .header("Authorization", authHeader).get();

                logger.debug("Querying the vehicle, response : {}, {}", response.getStatus(),
                        response.getStatusInfo().getReasonPhrase());

                if (!checkResponse(response, true)) {
                    logger.debug("An error occurred while querying the vehicle");
                    return null;
                }

                JsonObject jsonObject = JsonParser.parseString(response.readEntity(String.class)).getAsJsonObject();
                Vehicle[] vehicleArray = gson.fromJson(jsonObject.getAsJsonArray("response"), Vehicle[].class);

                for (Vehicle vehicle : vehicleArray) {
                    logger.debug("Querying the vehicle: VIN {}", vehicle.vin);
                    if (vehicle.vin.equals(getConfig().get(VIN))) {
                        vehicleJSON = gson.toJson(vehicle);
                        parseAndUpdate("queryVehicle", null, vehicleJSON);
                        if (logger.isTraceEnabled()) {
                            logger.trace("Vehicle is id {}/vehicle_id {}/tokens {}", vehicle.id, vehicle.vehicle_id,
                                    vehicle.tokens);
                        }
                        return vehicle;
                    }
                }
            } catch (ProcessingException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            }
        }
        return null;
    }

    protected void queryVehicleAndUpdate() {
        vehicle = queryVehicle();
        if (vehicle != null) {
            parseAndUpdate("queryVehicle", null, vehicleJSON);
        }
    }

    public void parseAndUpdate(String request, String payLoad, String result) {
        final Double LOCATION_THRESHOLD = .0000001;

        JsonObject jsonObject = null;

        try {
            if (request != null && result != null && !"null".equals(result)) {
                updateStatus(ThingStatus.ONLINE);
                // first, update state objects
                switch (request) {
                    case DRIVE_STATE: {
                        driveState = gson.fromJson(result, DriveState.class);

                        if (Math.abs(lastLatitude - driveState.latitude) > LOCATION_THRESHOLD
                                || Math.abs(lastLongitude - driveState.longitude) > LOCATION_THRESHOLD) {
                            logger.debug("Vehicle moved, resetting last location timestamp");

                            lastLatitude = driveState.latitude;
                            lastLongitude = driveState.longitude;
                            lastLocationChangeTimestamp = System.currentTimeMillis();
                        }

                        break;
                    }
                    case GUI_STATE: {
                        guiState = gson.fromJson(result, GUIState.class);
                        break;
                    }
                    case VEHICLE_STATE: {
                        vehicleState = gson.fromJson(result, VehicleState.class);
                        break;
                    }
                    case CHARGE_STATE: {
                        chargeState = gson.fromJson(result, ChargeState.class);
                        if (isCharging()) {
                            updateState(CHANNEL_CHARGE, OnOffType.ON);
                        } else {
                            updateState(CHANNEL_CHARGE, OnOffType.OFF);
                        }

                        break;
                    }
                    case CLIMATE_STATE: {
                        climateState = gson.fromJson(result, ClimateState.class);
                        BigDecimal avgtemp = roundBigDecimal(new BigDecimal(
                                (climateState.driver_temp_setting + climateState.passenger_temp_setting) / 2.0f));
                        updateState(CHANNEL_COMBINED_TEMP, new QuantityType<>(avgtemp, SIUnits.CELSIUS));
                        break;
                    }
                    case "queryVehicle": {
                        if (vehicle != null && !lastState.equals(vehicle.state)) {
                            lastState = vehicle.state;

                            // in case vehicle changed to awake, refresh all data
                            if (isAwake()) {
                                logger.debug("Vehicle is now awake, updating all data");
                                lastLocationChangeTimestamp = System.currentTimeMillis();
                                requestAllData();
                            }

                            setActive();
                        }

                        // reset timestamp if elapsed and set inactive to false
                        if (isInactive && lastStateTimestamp + (API_SLEEP_INTERVAL_MINUTES * 60 * 1000) < System
                                .currentTimeMillis()) {
                            logger.debug("Vehicle did not fall asleep within sleep period, checking again");
                            setActive();
                        } else {
                            boolean wasInactive = isInactive;
                            isInactive = !isCharging() && !hasMovedInSleepInterval();

                            if (!wasInactive && isInactive) {
                                lastStateTimestamp = System.currentTimeMillis();
                                logger.debug("Vehicle is inactive");
                            }
                        }

                        break;
                    }
                }

                // secondly, reformat the response string to a JSON compliant
                // object for some specific non-JSON compatible requests
                switch (request) {
                    case MOBILE_ENABLED_STATE: {
                        jsonObject = new JsonObject();
                        jsonObject.addProperty(MOBILE_ENABLED_STATE, result);
                        break;
                    }
                    default: {
                        jsonObject = JsonParser.parseString(result).getAsJsonObject();
                        break;
                    }
                }
            }

            // process the result
            if (jsonObject != null && result != null && !"null".equals(result)) {
                // deal with responses for "set" commands, which get confirmed
                // positively, or negatively, in which case a reason for failure
                // is provided
                if (jsonObject.get("reason") != null && jsonObject.get("reason").getAsString() != null) {
                    boolean requestResult = jsonObject.get("result").getAsBoolean();
                    logger.debug("The request ({}) execution was {}, and reported '{}'", new Object[] { request,
                            requestResult ? "successful" : "not successful", jsonObject.get("reason").getAsString() });
                } else {
                    Set<Map.Entry<String, JsonElement>> entrySet = jsonObject.entrySet();

                    long resultTimeStamp = 0;
                    for (Map.Entry<String, JsonElement> entry : entrySet) {
                        if ("timestamp".equals(entry.getKey())) {
                            resultTimeStamp = Long.valueOf(entry.getValue().getAsString());
                            if (logger.isTraceEnabled()) {
                                Date date = new Date(resultTimeStamp);
                                SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
                                logger.trace("The request result timestamp is {}", dateFormatter.format(date));
                            }
                            break;
                        }
                    }

                    try {
                        lock.lock();

                        boolean proceed = true;
                        if (resultTimeStamp < lastTimeStamp && request == DRIVE_STATE) {
                            proceed = false;
                        }

                        if (proceed) {
                            for (Map.Entry<String, JsonElement> entry : entrySet) {
                                try {
                                    TeslaChannelSelector selector = TeslaChannelSelector
                                            .getValueSelectorFromRESTID(entry.getKey());
                                    if (!selector.isProperty()) {
                                        if (!entry.getValue().isJsonNull()) {
                                            updateState(selector.getChannelID(), teslaChannelSelectorProxy.getState(
                                                    entry.getValue().getAsString(), selector, editProperties()));
                                            if (logger.isTraceEnabled()) {
                                                logger.trace(
                                                        "The variable/value pair '{}':'{}' is successfully processed",
                                                        entry.getKey(), entry.getValue());
                                            }
                                        } else {
                                            updateState(selector.getChannelID(), UnDefType.UNDEF);
                                        }
                                    } else {
                                        if (!entry.getValue().isJsonNull()) {
                                            Map<String, String> properties = editProperties();
                                            properties.put(selector.getChannelID(), entry.getValue().getAsString());
                                            updateProperties(properties);
                                            if (logger.isTraceEnabled()) {
                                                logger.trace(
                                                        "The variable/value pair '{}':'{}' is successfully used to set property '{}'",
                                                        entry.getKey(), entry.getValue(), selector.getChannelID());
                                            }
                                        }
                                    }
                                } catch (IllegalArgumentException e) {
                                    logger.trace("The variable/value pair '{}':'{}' is not (yet) supported",
                                            entry.getKey(), entry.getValue());
                                } catch (ClassCastException | IllegalStateException e) {
                                    logger.trace("An exception occurred while converting the JSON data : '{}'",
                                            e.getMessage(), e);
                                }
                            }
                        } else {
                            logger.warn("The result for request '{}' is discarded due to an out of sync timestamp",
                                    request);
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            }
        } catch (Exception p) {
            logger.error("An exception occurred while parsing data received from the vehicle: '{}'", p.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    protected QuantityType<Temperature> commandToQuantityType(Command command) {
        if (command instanceof QuantityType) {
            return ((QuantityType<Temperature>) command).toUnit(SIUnits.CELSIUS);
        }
        return new QuantityType<>(new BigDecimal(command.toString()), SIUnits.CELSIUS);
    }

    protected float quanityToRoundedFloat(QuantityType<Temperature> quantity) {
        return roundBigDecimal(quantity.toBigDecimal()).floatValue();
    }

    protected BigDecimal roundBigDecimal(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_EVEN);
    }

    protected Runnable slowStateRunnable = () -> {
        try {
            queryVehicleAndUpdate();

            boolean allowQuery = allowQuery();

            if (allowQuery) {
                requestData(CHARGE_STATE);
                requestData(CLIMATE_STATE);
                requestData(GUI_STATE);
                queryVehicle(MOBILE_ENABLED_STATE);
            } else {
                if (allowWakeUp) {
                    wakeUp();
                } else {
                    if (isAwake()) {
                        logger.debug("Vehicle is neither charging nor moving, skipping updates to allow it to sleep");
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Exception occurred in slowStateRunnable", e);
        }
    };

    protected Runnable fastStateRunnable = () -> {
        if (getThing().getStatus() == ThingStatus.ONLINE) {
            boolean allowQuery = allowQuery();

            if (allowQuery) {
                requestData(DRIVE_STATE);
                requestData(VEHICLE_STATE);
            } else {
                if (allowWakeUp) {
                    wakeUp();
                } else {
                    if (isAwake()) {
                        logger.debug("Vehicle is neither charging nor moving, skipping updates to allow it to sleep");
                    }
                }
            }
        }
    };
}
