#include <jni.h>
#include <string>
#include <pthread.h>
#include <unistd.h>
#include <sys/ptrace.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>

static bool check_tracer_and_maps() {
    FILE *fp = fopen("/proc/self/status", "r");
    if (fp) {
        char line[256];
        while (fgets(line, sizeof(line), fp)) {
            if (strncmp(line, "TracerPid:", 10) == 0) {
                int pid = atoi(line + 10);
                if (pid != 0) {
                    fclose(fp);
                    return false;
                }
                break;
            }
        }
        fclose(fp);
    }

    FILE *maps = fopen("/proc/self/maps", "r");
    if (maps) {
        char line[512];
        while (fgets(line, sizeof(line), maps)) {
            if (strstr(line, "frida") || strstr(line, "gum-js-loop") ||
                strstr(line, "xposed") || strstr(line, "substrate") ||
                strstr(line, "lsplant")) {
                fclose(maps);
                return false;
            }
        }
        fclose(maps);
    }

    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock >= 0) {
        struct sockaddr_in sa{};
        sa.sin_family = AF_INET;
        sa.sin_port = htons(27042);
        inet_aton("127.0.0.1", &sa.sin_addr);
        if (connect(sock, (struct sockaddr *)&sa, sizeof(sa)) == 0) {
            close(sock);
            return false;
        }
        close(sock);
    }
    return true;
}

static void *native_watchdog_thread(void *) {
    ptrace(PTRACE_TRACEME, 0, 0, 0);
    while (true) {
        if (!check_tracer_and_maps()) {
            _exit(1);
        }
        sleep(5);
    }
    return nullptr;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    pthread_t tid;
    pthread_create(&tid, nullptr, native_watchdog_thread, nullptr);
    pthread_detach(tid);
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_teleport_so2_AndroidBridge_nativeVerifyGuardState(JNIEnv *, jclass) {
    return check_tracer_and_maps() ? JNI_TRUE : JNI_FALSE;
}
