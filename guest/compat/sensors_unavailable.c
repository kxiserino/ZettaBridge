/* Optional native sensors are unavailable until a host sensor/looper bridge exists.
 * ponytail: expose an empty sensor inventory; add real queues and event marshalling
 * when enabling compass/accelerometer support. Never fabricate samples or success.
 * These run inside the ARM32 guest, so no host pointers cross the ABI boundary. */
#include <android/sensor.h>
#include <errno.h>

struct ASensorManager { unsigned char reserved; };
static struct ASensorManager manager_instance;

ASensorManager *ASensorManager_getInstance(void) { return &manager_instance; }

int ASensorManager_getSensorList(ASensorManager *manager, ASensorList *list) {
    if (manager != &manager_instance || list == NULL) return -EINVAL;
    *list = NULL;
    return 0;
}

ASensor const *ASensorManager_getDefaultSensor(ASensorManager *manager, int type) {
    (void)manager; (void)type;
    return NULL;
}

ASensorEventQueue *ASensorManager_createEventQueue(ASensorManager *manager,
        ALooper *looper, int ident, ALooper_callbackFunc callback, void *data) {
    (void)manager; (void)looper; (void)ident; (void)callback; (void)data;
    return NULL;
}

int ASensorManager_destroyEventQueue(ASensorManager *manager, ASensorEventQueue *queue) {
    (void)manager; (void)queue;
    return -EINVAL;
}

int ASensorEventQueue_enableSensor(ASensorEventQueue *queue, ASensor const *sensor) {
    (void)queue; (void)sensor;
    return -EINVAL;
}

int ASensorEventQueue_disableSensor(ASensorEventQueue *queue, ASensor const *sensor) {
    (void)queue; (void)sensor;
    return -EINVAL;
}

int ASensorEventQueue_setEventRate(ASensorEventQueue *queue, ASensor const *sensor, int32_t usec) {
    (void)queue; (void)sensor; (void)usec;
    return -EINVAL;
}

int ASensorEventQueue_hasEvents(ASensorEventQueue *queue) {
    (void)queue;
    return -EINVAL;
}

ssize_t ASensorEventQueue_getEvents(ASensorEventQueue *queue, ASensorEvent *events, size_t count) {
    (void)queue; (void)events; (void)count;
    return -EINVAL;
}

const char *ASensor_getName(ASensor const *sensor) { (void)sensor; return NULL; }
const char *ASensor_getVendor(ASensor const *sensor) { (void)sensor; return NULL; }
int ASensor_getType(ASensor const *sensor) { (void)sensor; return ASENSOR_TYPE_INVALID; }
float ASensor_getResolution(ASensor const *sensor) { (void)sensor; return 0.0f; }
int ASensor_getMinDelay(ASensor const *sensor) { (void)sensor; return -EINVAL; }
