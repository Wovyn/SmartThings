# Bosch
Various Bosch Device Handlers

## bosch-motion-detector-delay.groovy
 - this device handler adds a new preference that allows you to set the "Minimum Duration"
 - the Minimum Duration is set in minutes, and filters all "inactive" events until this time period is met
 - any additional "active" events will reset the Minimum Duration, pushing it out further
