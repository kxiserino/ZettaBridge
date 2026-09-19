/* T: rt_sigsuspend and guest-signal delivery to a thread blocked in futex. Boehm GC (IL2CPP)
 * suspends threads with SIGPWR and waits for each to call sigsuspend, so both working is what
 * stops Unity's stop-the-world handshake from stalling. */
#define _GNU_SOURCE
#include <errno.h>
#include <linux/futex.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

static volatile sig_atomic_t seen;
static volatile pid_t worker_tid;
static int futex_word;

static void on_usr1(int sig) { seen = sig; }

static void* worker(void* unused) {
    (void)unused;
    worker_tid = (pid_t)syscall(SYS_gettid);
    /* A 2 s timeout turns a missed signal into a FAIL instead of an infinite hang. */
    struct timespec limit = {2, 0};
    long r = syscall(SYS_futex, &futex_word, FUTEX_WAIT, 0, &limit, NULL, 0);
    const int ok = r == -1 && errno == EINTR && seen == SIGUSR1;
    printf("futex-signal=%s r=%ld errno=%d seen=%d\n", ok ? "PASS" : "FAIL", r, errno, (int)seen);
    return NULL;
}

int main(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_usr1;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGUSR1, &sa, NULL);

    pthread_t thread;
    pthread_create(&thread, NULL, worker, NULL);
    while (worker_tid == 0) {}
    usleep(20000);
    syscall(SYS_tkill, worker_tid, SIGUSR1);
    pthread_join(thread, NULL);

    /* sigsuspend: a signal that is already pending under the old mask becomes deliverable with
     * the temporary mask, so sigsuspend returns -EINTR after its handler ran. */
    seen = 0;
    sigset_t blocked;
    sigemptyset(&blocked);
    sigaddset(&blocked, SIGUSR1);
    sigprocmask(SIG_BLOCK, &blocked, NULL);
    syscall(SYS_tkill, (pid_t)syscall(SYS_gettid), SIGUSR1);
    sigset_t temporary;
    sigemptyset(&temporary);
    errno = 0;
    const int r = sigsuspend(&temporary);
    const int ok = r == -1 && errno == EINTR && seen == SIGUSR1;
    printf("sigsuspend=%s r=%d errno=%d seen=%d\n", ok ? "PASS" : "FAIL", r, errno, (int)seen);

    fflush(stdout);
    return 0;
}
