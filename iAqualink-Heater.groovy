metadata {
    definition(
            name: "iAqualink Heater",
            namespace: "iAqualink",
            author: "Vyrolan",
            importUrl: "https://raw.githubusercontent.com/Vyrolan/iAqualink-Hubitat/main/iAqualink-Heater.groovy"
    ) {
        capability "TemperatureMeasurement"
        capability "Refresh"

        attribute "heatingSetpoint", "number"
        attribute "thermostatMode", "enum", ["off", "heat", "emergency heat"]
        attribute "thermostatOperatingState", "enum", ["idle", "pending heat", "heating"]

        command "heat"
        command "off"
        command "setHeatingSetpoint", [[name: "temperature*", type: "NUMBER"]]
        command "setThermostatMode", [[name: "mode*", type: "ENUM", constraints: ["heat", "off"]]]
    }

    preferences {
        section {
            input(
                    name: "debugLogEnable",
                    type: "bool",
                    title: "Enable debug logging",
                    defaultValue: false
            )
            input(
                    name: "infoLogEnable",
                    type: "bool",
                    title: "Enable info logging",
                    defaultValue: false
            )
            input(
                    name: "autoDisableDebugLog",
                    type: "number",
                    title: "Auto-disable debug logging",
                    description: "Automatically disable debug logging after this number of minutes (0 = Do not disable)",
                    defaultValue: 15
            )
            input(
                    name: "autoDisableInfoLog",
                    type: "number",
                    title: "Disable Info Logging",
                    description: "Automatically disable info logging after this number of minutes (0 = Do not disable)",
                    defaultValue: 15
            )
        }
    }
}

// [Driver API] Called when Device is first created
void installed() {
    unschedule()
    if (settings.debugLogEnable && settings.autoDisableDebugLog > 0)
        runIn(settings.autoDisableDebugLog * 60, disableDebugLog)
    if (settings.infoLogEnable && settings.autoDisableInfoLog > 0)
        runIn(settings.autoDisableInfoLog * 60, disableInfoLog)

    state.heaterEnabled = false

    sendEvent(name: "temperature", value: 0)
    sendEvent(name: "heatingSetpoint", value: 0)
    sendEvent(name: "thermostatMode", value: "off")
    sendEvent(name: "thermostatOperatingState", value: "idle")
}

// [Driver API] Called when Device's preferences are changed
void updated() {
    infoLog("Preferences changed...")
    installed()
}

// [Driver API] Called when Device receives a message
void parse(String description) { }

void debugLog(String msg) {
    if (settings.debugLogEnable)
        log.debug("${device.label?device.label:device.name}: ${msg}")
}

void infoLog(String msg) {
    if (settings.infoLogEnable)
        log.info("${device.label?device.label:device.name}: ${msg}")
}

void warnLog(String msg) {
    log.warn("${device.label?device.label:device.name}: ${msg}")
}

void errorLog(String msg) {
    log.error("${device.label?device.label:device.name}: ${msg}")
}

void disableDebugLog() {
    infoLog("Automatically disabling debug logging after ${settings.autoDisableDebugLog} minutes.")
    device.updateSetting("debugLogEnable", [value: "false", type: "bool"])
}

void disableInfoLog() {
    infoLog("Automatically disabling info logging after ${settings.autoDisableInfoLog} minutes.")
    device.updateSetting("infoLogEnable", [value: "false", type: "bool"])
}

// [Capability Refresh]
void refresh() {
    parent.refresh()
}

void setType(String heaterType, String tempVar) {
    state.heaterType = heaterType
    state.tempVar = tempVar
}

void updateState(String heaterStatus, String freezeProtect, Integer currentTemp, Integer targetTemp, String configTempScale, String iaqualinkTempScale) {
    if (freezeProtect == "on") {
        sendEvent(name: "thermostatMode", value: "emergency heat")
        sendEvent(name: "thermostatOperatingState", value: "heating")
    }
    else if (heaterStatus != "off") {
        sendEvent(name: "thermostatMode", value: "heat")
        def operatingState = (heaterStatus == "heating") ? "heating" : "pending heat"
        sendEvent(name: "thermostatOperatingState", value: operatingState)
    }
    else {
        sendEvent(name: "thermostatMode", value: "off")
        sendEvent(name: "thermostatOperatingState", value: "idle")
    }

    state.heaterEnabled = (heaterStatus != "off")
    state.tempScale = configTempScale
    state.iaqualinkTempScale = iaqualinkTempScale

    sendEvent(name: "temperature", value: currentTemp, unit: configTempScale)
    sendEvent(name: "heatingSetpoint", value: targetTemp, unit: configTempScale)
}

void setThermostatMode(String thermostatmode) {
    if (thermostatmode == "off")
        off()
    else
        heat()
}

void heat() {
    if (state.heaterEnabled) {
        debugLog("${device.name} is already enabled")
        return
    }
    def msg = "Enabling ${device.name}"
    infoLog(msg)
    parent.doCommand(msg, "set_${state.heaterType}_heater")
    sendEvent(name: 'thermostatMode', value: "heat")
    if ((device.currentValue("temperature") as Integer) < (device.currentValue("heatingSetpoint") as Integer)) {
        sendEvent(name: "thermostatOperatingState", value: "heating")
        parent.updateAttribute(name: "${state.heaterType}_heater", value: "heating")
    }
    else {
        sendEvent(name: "thermostatOperatingState", value: "pending heat")
        parent.updateAttribute(name: "${state.heaterType}_heater", value: "enabled")
    }
}

void off() {
    if (!(state.heaterEnabled)) {
        debugLog("${device.name} is already disabled")
        return
    }
    def msg = "Disabling ${device.name}"
    infoLog(msg)
    parent.doCommand(msg, "set_${state.heaterType}_heater")
    sendEvent(name: 'thermostatMode', value: "off")
    sendEvent(name: "thermostatOperatingState", value: "idle")
    parent.updateAttribute(name: "${state.heaterType}_heater", value: "off")
}

Integer convertTemperatureForDevice(Integer temperature) {
    if (state.iaqualinkTempScale == state.tempScale)
        return temperature
    else if (state.tempScale == "F")
        return fahrenheitToCelsius(temperature) as Integer
    else // if (state.tempScale == "C")
        return celsiusToFahrenheit(temperature) as Integer
}

void setHeatingSetpoint(temperature) {
    def msg = "Setting Heating Target to ${temperature} degrees for ${device.name}"
    infoLog(msg)
    parent.doCommand(msg, "set_temps", [("${state.tempVar}" as String): convertTemperatureForDevice(temperature as Integer) as String])
    sendEvent(name: "heatingSetpoint", value: temperature as Integer, unit: state.tempScale)
    parent.updateAttribute(name: "${state.heaterType}_set_point", value: temperature as String, unit: state.tempScale)
}
