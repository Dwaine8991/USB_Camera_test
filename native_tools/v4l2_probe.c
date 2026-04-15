#include <errno.h>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <poll.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/time.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define MAX_BUFFERS 4

struct buffer {
    void *start;
    size_t length;
};

static int xioctl(int fd, unsigned long request, void *arg) {
    int rc;
    do {
        rc = ioctl(fd, request, arg);
    } while (rc == -1 && errno == EINTR);
    return rc;
}

static const char *capability_name(uint32_t caps) {
    static char buf[256];
    buf[0] = '\0';
    if (caps & V4L2_CAP_VIDEO_CAPTURE) strcat(buf, "VIDEO_CAPTURE ");
    if (caps & V4L2_CAP_VIDEO_CAPTURE_MPLANE) strcat(buf, "VIDEO_CAPTURE_MPLANE ");
    if (caps & V4L2_CAP_STREAMING) strcat(buf, "STREAMING ");
    if (caps & V4L2_CAP_READWRITE) strcat(buf, "READWRITE ");
    if (caps & V4L2_CAP_DEVICE_CAPS) strcat(buf, "DEVICE_CAPS ");
    return buf;
}

static void fourcc_to_string(uint32_t fourcc, char out[5]) {
    out[0] = (char)(fourcc & 0xff);
    out[1] = (char)((fourcc >> 8) & 0xff);
    out[2] = (char)((fourcc >> 16) & 0xff);
    out[3] = (char)((fourcc >> 24) & 0xff);
    out[4] = '\0';
}

static void list_formats(int fd) {
    struct v4l2_fmtdesc fmt;
    memset(&fmt, 0, sizeof(fmt));
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;

    while (xioctl(fd, VIDIOC_ENUM_FMT, &fmt) == 0) {
        char fourcc[5];
        fourcc_to_string(fmt.pixelformat, fourcc);
        printf("FORMAT index=%u fourcc=%s desc=%s\n", fmt.index, fourcc, fmt.description);

        struct v4l2_frmsizeenum size;
        memset(&size, 0, sizeof(size));
        size.pixel_format = fmt.pixelformat;
        while (xioctl(fd, VIDIOC_ENUM_FRAMESIZES, &size) == 0) {
            if (size.type == V4L2_FRMSIZE_TYPE_DISCRETE) {
                printf("  SIZE %ux%u\n", size.discrete.width, size.discrete.height);
                struct v4l2_frmivalenum ival;
                memset(&ival, 0, sizeof(ival));
                ival.pixel_format = fmt.pixelformat;
                ival.width = size.discrete.width;
                ival.height = size.discrete.height;
                while (xioctl(fd, VIDIOC_ENUM_FRAMEINTERVALS, &ival) == 0) {
                    if (ival.type == V4L2_FRMIVAL_TYPE_DISCRETE && ival.discrete.numerator != 0) {
                        double fps = (double)ival.discrete.denominator / (double)ival.discrete.numerator;
                        printf("    FPS %.3f (%u/%u)\n",
                               fps,
                               ival.discrete.denominator,
                               ival.discrete.numerator);
                    }
                    ival.index++;
                }
            }
            size.index++;
        }
        fmt.index++;
    }
}

static int try_set_format(int fd, uint32_t width, uint32_t height, uint32_t pixfmt) {
    struct v4l2_format fmt;
    memset(&fmt, 0, sizeof(fmt));
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    fmt.fmt.pix.width = width;
    fmt.fmt.pix.height = height;
    fmt.fmt.pix.pixelformat = pixfmt;
    fmt.fmt.pix.field = V4L2_FIELD_ANY;

    if (xioctl(fd, VIDIOC_S_FMT, &fmt) < 0) {
        perror("VIDIOC_S_FMT");
        return -1;
    }

    char fourcc[5];
    fourcc_to_string(fmt.fmt.pix.pixelformat, fourcc);
    printf("SET_FMT actual=%ux%u fourcc=%s bytesperline=%u sizeimage=%u\n",
           fmt.fmt.pix.width,
           fmt.fmt.pix.height,
           fourcc,
           fmt.fmt.pix.bytesperline,
           fmt.fmt.pix.sizeimage);
    if (fmt.fmt.pix.width != width || fmt.fmt.pix.height != height || fmt.fmt.pix.pixelformat != pixfmt) {
        fprintf(stderr, "Requested format was adjusted by driver.\n");
        return -2;
    }
    return 0;
}

static int try_set_fps(int fd, uint32_t fps) {
    struct v4l2_streamparm parm;
    memset(&parm, 0, sizeof(parm));
    parm.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    parm.parm.capture.timeperframe.numerator = 1;
    parm.parm.capture.timeperframe.denominator = fps;

    if (xioctl(fd, VIDIOC_S_PARM, &parm) < 0) {
        perror("VIDIOC_S_PARM");
        return -1;
    }
    printf("SET_PARM actual_fps=%.3f (%u/%u)\n",
           parm.parm.capture.timeperframe.numerator
                   ? (double)parm.parm.capture.timeperframe.denominator /
                             (double)parm.parm.capture.timeperframe.numerator
                   : 0.0,
           parm.parm.capture.timeperframe.denominator,
           parm.parm.capture.timeperframe.numerator);
    return 0;
}

static int stream_test(int fd, uint32_t width, uint32_t height, uint32_t pixfmt, uint32_t fps, int seconds) {
    if (try_set_format(fd, width, height, pixfmt) < 0) {
        return 1;
    }
    try_set_fps(fd, fps);

    struct v4l2_requestbuffers req;
    memset(&req, 0, sizeof(req));
    req.count = MAX_BUFFERS;
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    req.memory = V4L2_MEMORY_MMAP;
    if (xioctl(fd, VIDIOC_REQBUFS, &req) < 0) {
        perror("VIDIOC_REQBUFS");
        return 1;
    }

    if (req.count < 2) {
        fprintf(stderr, "Insufficient buffers: %u\n", req.count);
        return 1;
    }

    struct buffer buffers[MAX_BUFFERS];
    memset(buffers, 0, sizeof(buffers));

    for (uint32_t i = 0; i < req.count && i < MAX_BUFFERS; ++i) {
        struct v4l2_buffer buf;
        memset(&buf, 0, sizeof(buf));
        buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;
        if (xioctl(fd, VIDIOC_QUERYBUF, &buf) < 0) {
            perror("VIDIOC_QUERYBUF");
            return 1;
        }

        buffers[i].length = buf.length;
        buffers[i].start = mmap(NULL, buf.length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, buf.m.offset);
        if (buffers[i].start == MAP_FAILED) {
            perror("mmap");
            return 1;
        }

        if (xioctl(fd, VIDIOC_QBUF, &buf) < 0) {
            perror("VIDIOC_QBUF");
            return 1;
        }
    }

    enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (xioctl(fd, VIDIOC_STREAMON, &type) < 0) {
        perror("VIDIOC_STREAMON");
        return 1;
    }

    struct timespec start_time;
    clock_gettime(CLOCK_MONOTONIC, &start_time);
    int frame_count = 0;
    long long total_bytes = 0;
    int window_frames = 0;
    struct timespec window_start = start_time;
    double min_window_fps = 0.0;
    double max_window_fps = 0.0;
    int window_samples = 0;

    while (1) {
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        double elapsed = (now.tv_sec - start_time.tv_sec) + (now.tv_nsec - start_time.tv_nsec) / 1000000000.0;
        if (elapsed >= seconds) {
            break;
        }

        struct pollfd pfd;
        memset(&pfd, 0, sizeof(pfd));
        pfd.fd = fd;
        pfd.events = POLLIN;
        int prc = poll(&pfd, 1, 2000);
        if (prc <= 0) {
            fprintf(stderr, "poll timeout/error prc=%d errno=%d\n", prc, errno);
            break;
        }

        struct v4l2_buffer buf;
        memset(&buf, 0, sizeof(buf));
        buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        buf.memory = V4L2_MEMORY_MMAP;
        if (xioctl(fd, VIDIOC_DQBUF, &buf) < 0) {
            perror("VIDIOC_DQBUF");
            break;
        }

        frame_count++;
        window_frames++;
        total_bytes += buf.bytesused;

        double window_elapsed =
                (now.tv_sec - window_start.tv_sec) + (now.tv_nsec - window_start.tv_nsec) / 1000000000.0;
        if (window_elapsed >= 1.0) {
            double window_fps = window_frames / window_elapsed;
            if (window_samples == 0 || window_fps < min_window_fps) {
                min_window_fps = window_fps;
            }
            if (window_samples == 0 || window_fps > max_window_fps) {
                max_window_fps = window_fps;
            }
            window_samples++;
            printf("WINDOW second=%d frames=%d elapsed=%.3f fps=%.3f\n",
                   window_samples,
                   window_frames,
                   window_elapsed,
                   window_fps);
            window_start = now;
            window_frames = 0;
        }

        if (xioctl(fd, VIDIOC_QBUF, &buf) < 0) {
            perror("VIDIOC_QBUF(requeue)");
            break;
        }
    }

    if (xioctl(fd, VIDIOC_STREAMOFF, &type) < 0) {
        perror("VIDIOC_STREAMOFF");
    }

    for (uint32_t i = 0; i < req.count && i < MAX_BUFFERS; ++i) {
        if (buffers[i].start && buffers[i].start != MAP_FAILED) {
            munmap(buffers[i].start, buffers[i].length);
        }
    }

    struct timespec end_time;
    clock_gettime(CLOCK_MONOTONIC, &end_time);
    double elapsed = (end_time.tv_sec - start_time.tv_sec) + (end_time.tv_nsec - start_time.tv_nsec) / 1000000000.0;
    double actual_fps = elapsed > 0.0 ? frame_count / elapsed : 0.0;
    printf("STREAM_RESULT frames=%d elapsed=%.3f fps=%.3f avg_bytes=%.1f window_min_fps=%.3f window_max_fps=%.3f samples=%d\n",
           frame_count,
           elapsed,
           actual_fps,
           frame_count > 0 ? (double)total_bytes / frame_count : 0.0,
           min_window_fps,
           max_window_fps,
           window_samples);
    return 0;
}

static uint32_t parse_fourcc(const char *s) {
    if (strlen(s) != 4) {
        fprintf(stderr, "fourcc must be 4 chars\n");
        exit(2);
    }
    return v4l2_fourcc(s[0], s[1], s[2], s[3]);
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage:\n");
        fprintf(stderr, "  %s /dev/videoX\n", argv[0]);
        fprintf(stderr, "  %s /dev/videoX --stream MJPG 1920 1080 60 5\n", argv[0]);
        return 2;
    }

    const char *dev = argv[1];
    int fd = open(dev, O_RDWR | O_NONBLOCK);
    if (fd < 0) {
        perror("open");
        return 1;
    }

    struct v4l2_capability cap;
    memset(&cap, 0, sizeof(cap));
    if (xioctl(fd, VIDIOC_QUERYCAP, &cap) < 0) {
        perror("VIDIOC_QUERYCAP");
        close(fd);
        return 1;
    }

    printf("DEVICE %s\n", dev);
    printf("driver=%s card=%s bus=%s version=%u.%u.%u caps=0x%x device_caps=0x%x %s\n",
           cap.driver,
           cap.card,
           cap.bus_info,
           (cap.version >> 16) & 0xff,
           (cap.version >> 8) & 0xff,
           cap.version & 0xff,
           cap.capabilities,
           cap.device_caps,
           capability_name(cap.device_caps ? cap.device_caps : cap.capabilities));

    list_formats(fd);

    if (argc >= 8 && strcmp(argv[2], "--stream") == 0) {
        uint32_t pixfmt = parse_fourcc(argv[3]);
        uint32_t width = (uint32_t)strtoul(argv[4], NULL, 10);
        uint32_t height = (uint32_t)strtoul(argv[5], NULL, 10);
        uint32_t fps = (uint32_t)strtoul(argv[6], NULL, 10);
        int seconds = atoi(argv[7]);
        int rc = stream_test(fd, width, height, pixfmt, fps, seconds);
        close(fd);
        return rc;
    }

    close(fd);
    return 0;
}
