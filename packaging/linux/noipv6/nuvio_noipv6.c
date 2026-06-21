#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <netdb.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>

typedef int (*socket_fn_t)(int domain, int type, int protocol);
typedef int (*getaddrinfo_fn_t)(const char *node, const char *service,
                                const struct addrinfo *hints,
                                struct addrinfo **res);

int socket(int domain, int type, int protocol) {
    static socket_fn_t real_socket = NULL;
    if (!real_socket) {
        real_socket = (socket_fn_t)dlsym(RTLD_NEXT, "socket");
    }
    if (!real_socket) {
        errno = ENOSYS;
        return -1;
    }

    if (domain == AF_INET6) {
        errno = EAFNOSUPPORT;
        return -1;
    }

    return real_socket(domain, type, protocol);
}

int getaddrinfo(const char *node, const char *service,
                const struct addrinfo *hints,
                struct addrinfo **res) {
    static getaddrinfo_fn_t real_getaddrinfo = NULL;
    if (!real_getaddrinfo) {
        real_getaddrinfo = (getaddrinfo_fn_t)dlsym(RTLD_NEXT, "getaddrinfo");
    }
    if (!real_getaddrinfo) {
        return EAI_SYSTEM;
    }

    struct addrinfo ipv4_hints;
    const struct addrinfo *effective_hints = hints;

    if (hints == NULL || hints->ai_family == AF_UNSPEC) {
        memset(&ipv4_hints, 0, sizeof(ipv4_hints));
        if (hints != NULL) {
            ipv4_hints = *hints;
        }
        ipv4_hints.ai_family = AF_INET;
        effective_hints = &ipv4_hints;
    }

    return real_getaddrinfo(node, service, effective_hints, res);
}
