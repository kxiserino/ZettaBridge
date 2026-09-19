/* Run against the guest libandroid, not the host NDK library. */
#include <android/sensor.h>
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    ASensorManager *manager = ASensorManager_getInstance();
    assert(manager != NULL && manager == ASensorManager_getInstance());
    ASensorList list = (ASensorList)(uintptr_t)1;
    assert(ASensorManager_getSensorList(manager, &list) == 0 && list == NULL);
    assert(ASensorManager_getSensorList(NULL, &list) == -EINVAL);
    assert(ASensorManager_getSensorList(manager, NULL) == -EINVAL);
    assert(ASensorManager_getDefaultSensor(manager, ASENSOR_TYPE_ACCELEROMETER) == NULL);
    assert(ASensorManager_getDefaultSensor(manager, ASENSOR_TYPE_MAGNETIC_FIELD) == NULL);
    assert(ASensorManager_createEventQueue(manager, NULL, 0, NULL, NULL) == NULL);
    assert(ASensorManager_destroyEventQueue(manager, NULL) == -EINVAL);
    assert(ASensorEventQueue_enableSensor(NULL, NULL) == -EINVAL);
    assert(ASensorEventQueue_disableSensor(NULL, NULL) == -EINVAL);
    assert(ASensorEventQueue_setEventRate(NULL, NULL, 10000) == -EINVAL);
    assert(ASensorEventQueue_hasEvents(NULL) == -EINVAL);
    ASensorEvent event, before;
    memset(&event, 0xa5, sizeof event);
    memcpy(&before, &event, sizeof event);
    assert(ASensorEventQueue_getEvents(NULL, &event, 1) == -EINVAL);
    assert(memcmp(&event, &before, sizeof event) == 0);
    assert(ASensor_getName(NULL) == NULL && ASensor_getVendor(NULL) == NULL);
    assert(ASensor_getType(NULL) == ASENSOR_TYPE_INVALID);
    assert(ASensor_getResolution(NULL) == 0.0f);
    assert(ASensor_getMinDelay(NULL) == -EINVAL);
    puts("sensor unavailable PASS");
    return 0;
}
