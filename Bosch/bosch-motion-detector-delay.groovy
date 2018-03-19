/*
 *  Copyright 2018 Scott Lemon
 *		forked from a work by:
 *  Copyright 2017 Tomas Axerot
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not
 *  use this file except in compliance with the License. You may obtain a copy
 *  of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 *
 *
 * Forked from the project: https://github.com/tomasaxerot/SmartThings/tree/master/devicetypes/tomasaxerot/bosch-motion-detector.src
 *
 * v1.2 - added extra checks for null/unconfigured minDuration
 * v1.1 - updated some of the log statements to clarify operation
 * v1.0 - added first attempt at implementing "Minimum Duration" (minutes) to prevent rapid active/inactive events
 *
 */
import physicalgraph.zigbee.clusters.iaszone.ZoneStatus


metadata {
	definition(name: "Bosch Motion Detector with Delay", namespace: "Wovyn", author: "Scott Lemon") {
		capability "Motion Sensor"
		capability "Configuration"
		capability "Battery"
		capability "Temperature Measurement"
		capability "Refresh"
		capability "Health Check"
		capability "Sensor"

		command "enrollResponse"

		fingerprint inClusters: "0000,0001,0003,0402,0500,0020,0B05", outClusters: "0019", manufacturer: "Bosch", model: "ISW-ZDL1-WP11G", deviceJoinName: "Bosch TriTech Motion Detector"
        fingerprint inClusters: "0000,0001,0003,0402,0500,0020,0B05", outClusters: "0019", manufacturer: "Bosch", model: "ISW-ZPR1-WP13", deviceJoinName: "Bosch PIR Motion Detector"
	}

	simulator {
		status "active": "zone report :: type: 19 value: 0031"
		status "inactive": "zone report :: type: 19 value: 0030"
	}

	preferences {
		section {
			image(name: 'educationalcontent', multiple: true, images: [
					"http://cdn.device-gse.smartthings.com/Motion/Motion1.jpg",
					"http://cdn.device-gse.smartthings.com/Motion/Motion2.jpg",
					"http://cdn.device-gse.smartthings.com/Motion/Motion3.jpg"
			])
		}
		section {
			input "minDuration", "number", title: "Minimum Active Duration", description: "Minimum minutes before inactive event", range: "0..*", displayDuringSetup: true
			input title: "Temperature Offset", description: "This feature allows you to correct any temperature variations by selecting an offset. Ex: If your sensor consistently reports a temp that's 5 degrees too warm, you'd enter '-5'. If 3 degrees too cold, enter '+3'.", displayDuringSetup: false, type: "paragraph", element: "paragraph"
			input "tempOffset", "number", title: "Degrees", description: "Adjust temperature by this many degrees", range: "*..*", displayDuringSetup: false
		}
	}

	tiles(scale: 2) {
		multiAttributeTile(name: "motion", type: "generic", width: 6, height: 4) {
			tileAttribute("device.motion", key: "PRIMARY_CONTROL") {
				attributeState "active", label: 'motion', icon: "st.motion.motion.active", backgroundColor: "#00A0DC"
				attributeState "inactive", label: 'no motion', icon: "st.motion.motion.inactive", backgroundColor: "#cccccc"
			}
		}
		valueTile("temperature", "device.temperature", width: 2, height: 2) {
			state("temperature", label: '${currentValue}°', unit: "F",
					backgroundColors: [
							[value: 31, color: "#153591"],
							[value: 44, color: "#1e9cbb"],
							[value: 59, color: "#90d2a7"],
							[value: 74, color: "#44b621"],
							[value: 84, color: "#f1d801"],
							[value: 95, color: "#d04e00"],
							[value: 96, color: "#bc2323"]
					]
			)
		}
		valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false, width: 2, height: 2) {
			state "battery", label: '${currentValue}% battery', unit: ""
		}
		standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "default", action: "refresh.refresh", icon: "st.secondary.refresh"
		}

		main(["motion", "temperature"])
		details(["motion", "temperature", "battery", "refresh"])
	}
}

def parse(String description) {
	log.debug "description: $description"
	Map map = zigbee.getEvent(description)
	if (!map) {
		if (description?.startsWith('zone status')) {
			map = parseIasMessage(description)
		} else {
			Map descMap = zigbee.parseDescriptionAsMap(description)
			// is this a battery report?
			if (descMap?.clusterInt == 0x0001 && descMap.attrInt == 0x0020 && descMap.commandInt != 0x07 && descMap?.value) {
				map = getBatteryResult(Integer.parseInt(descMap.value, 16))
			// else is it a temperature configuration response?
			} else if (descMap?.clusterInt == zigbee.TEMPERATURE_MEASUREMENT_CLUSTER && descMap.commandInt == 0x07) {
				if (descMap.data[0] == "00") {
					log.debug "TEMP REPORTING CONFIG RESPONSE: $descMap"
					sendEvent(name: "checkInterval", value: 60 * 12, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])
				} else {
					log.warn "TEMP REPORTING CONFIG FAILED- error code: ${descMap.data[0]}"
				}
			// else is it a motion report?
			} else if (descMap.clusterInt == 0x0406 && descMap.attrInt == 0x0000) {
				def value = descMap.value.endsWith("01") ? "active" : "inactive"
				// if this is an active event ...
				log.debug "Doing a read attr motion event"
				map = getMotionResult(value)
               	log.debug "motion event: $value"

			}
		}
	// else is it a temperature report?
	} else if (map.name == "temperature") {
		if (tempOffset) {
			map.value = (int) map.value + (int) tempOffset
		}
		map.descriptionText = temperatureScale == 'C' ? '{{ device.displayName }} was {{ value }}°C' : '{{ device.displayName }} was {{ value }}°F'
		map.translatable = true
	}

	log.debug "Parse returned $map"
	def result = null
	if (map.name == "motion") {
		log.debug "state.inMotion is: $state.inMotion"
		log.debug "minDuration: $minDuration"
		if (state.inMotion != true) {		// are we NOT inMotion?
			if (map.value == "active") {
            	log.debug "New motion event - not already inMotion"
				result = map ? createEvent(map) : [:]
				// did the user set a minimum duration?
                if (minDuration != null && minDuration > 0) {
                	state.inMotion = true
					log.debug "Set state.inMotion to: $state.inMotion"
					runIn(minDuration * 60, "delayedMotionInactive", [data: getMotionResult("inactive")])
                }
            } else {
            	// we are NOT inMotion, so handle the inactive event
                log.debug "Inactive event - only create if duration == null || duration == 0"
				if (minDuration == null || minDuration == 0) {
                	state.inMotion = false
					log.debug "Set state.inMotion to: $state.inMotion"
					result = map ? createEvent(map) : [:]
                }
			}
		} else {
			// we are already inMotion, then if it's another active message reschedule the inactive event
            if (map.value == "active") {
            	log.debug "New motion event - already inMotion, rescedule inactive event"
                if (minDuration != null && minDuration > 0) {
                	state.inMotion = true
					log.debug "Set state.inMotion to: $state.inMotion"
					runIn(minDuration * 60, "delayedMotionInactive", [data: getMotionResult("inactive")])
                } else {
					log.debug "Reschedule abort: minDuration null or 0!"
				}
            } else {
            	// we are inMotion, and so we ignore the inactive event!
            	log.debug "Inactive event - already inMotion, ignoring it!"
            }
		}
	} else {
    	// process other map events
		result = map ? createEvent(map) : [:]
	}

	if (description?.startsWith('enroll request')) {
		List cmds = zigbee.enrollResponse()
		log.debug "enroll response: ${cmds}"
		result = cmds?.collect { new physicalgraph.device.HubAction(it) }
	}
	return result
}

private Map getMotionResult(value) {
	String descriptionText = value == 'active' ? "{{ device.displayName }} detected motion" : "{{ device.displayName }} motion has stopped"
	return [
			name           : 'motion',
			value          : value,
			descriptionText: descriptionText,
			translatable   : true
	]
}

def delayedMotionInactive(event_map) {
    log.debug "delayedMotionInactive handler method called with map: $event_map"
	sendEvent(event_map)
	state.inMotion = false
	log.debug "Set state.inMotion to: $state.inMotion"
}

private Map parseIasMessage(String description) {
	ZoneStatus zs = zigbee.parseZoneStatus(description)

	// Some sensor models that use this DTH use alarm1 and some use alarm2 to signify motion
	return (zs.isAlarm1Set() || zs.isAlarm2Set()) ? getMotionResult('active') : getMotionResult('inactive')
}

private Map getBatteryResult(rawValue) {
	log.debug "Battery rawValue = ${rawValue}"
	def linkText = getLinkText(device)

	def result = [:]

	//ISW-ZPR1-WP13 uses 4 batteries, 2 are used in measurement
    //ISW-ZDL1-WP11G uses 6 batteries, 4 are used in measurement 
    
	if (!(rawValue == 0 || rawValue == 255)) {
		result.name = 'battery'
		result.translatable = true
		result.descriptionText = "{{ device.displayName }} battery was {{ value }}%"		
        
		def model = device.getDataValue("model")
        def volts = rawValue // For the batteryMap to work the key needs to be an int
		def batteryMap = []
        def minVolts = 0
		def maxVolts = 0
                          
        if (model == "ISW-ZDL1-WP11G") {	
        	batteryMap = [60: 100, 59: 100, 58: 100, 57: 100, 56: 100, 55: 100,            
        				  54: 100, 53: 100, 52: 100, 51: 100, 50: 90, 49: 90,
                          48: 90, 47: 90, 46: 70, 45: 70, 44: 70, 43: 70, 42: 50, 
                          41: 50, 40: 50, 39: 50, 38: 30, 37: 30, 36: 30, 35: 30,
                          34: 15, 33: 15, 32: 1, 31: 1, 30: 0]                          
        	minVolts = 30        
            maxVolts = 60			           
        } else if(model == "ISW-ZPR1-WP13") {
        	batteryMap = [30: 100, 29: 100, 28: 100, 27: 100, 26: 100, 25: 90, 24: 90, 23: 70,
							  22: 70, 21: 50, 20: 50, 19: 30, 18: 30, 17: 15, 16: 1, 15: 0]
			minVolts = 15
			maxVolts = 30         
        } else {
        	result.value = 0
            return result
        }

		if (volts < minVolts)
			volts = minVolts
		else if (volts > maxVolts)
			volts = maxVolts
		
		result.value = batteryMap[volts]		
	}

	return result
}

/**
 * PING is used by Device-Watch in attempt to reach the Device
 * */
def ping() {
	return zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020) // Read the Battery Level
}

def refresh() {
	log.debug "refresh called"

	def refreshCmds = zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020) +
			zigbee.readAttribute(zigbee.TEMPERATURE_MEASUREMENT_CLUSTER, 0x0000)

	return refreshCmds + zigbee.enrollResponse()
}

def configure() {
	// Device-Watch allows 2 check-in misses from device + ping (plus 1 min lag time)
	// enrolls with default periodic reporting until newer 5 min interval is confirmed
	sendEvent(name: "checkInterval", value: 2 * 60 * 60 + 1 * 60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])

	// temperature minReportTime 30 seconds, maxReportTime 5 min. Reporting interval if no activity
	// battery minReport 30 seconds, maxReportTime 6 hrs by default
	return refresh() + zigbee.batteryConfig() + zigbee.temperatureConfig(30, 300) // send refresh cmds as part of config
}