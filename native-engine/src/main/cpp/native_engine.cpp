#include "native_engine.h"

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_pocketserver_nativeengine_NativeEngine_nativeIs16KbAligned(
    JNIEnv* env,
    jobject /* thiz */,
    jstring file_path
) {
    if (file_path == nullptr) {
        return JNI_FALSE;
    }

    const char* path = env->GetStringUTFChars(file_path, nullptr);
    if (path == nullptr) {
        return JNI_FALSE;
    }

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        LOGE("Failed to open file for ELF validation: %s, errno=%d (%s)", path, errno, strerror(errno));
        env->ReleaseStringUTFChars(file_path, path);
        return JNI_FALSE;
    }

    Elf64_Ehdr ehdr;
    ssize_t bytes_read = read(fd, &ehdr, sizeof(ehdr));
    if (bytes_read != sizeof(ehdr)) {
        LOGE("Failed to read ELF64 header from %s", path);
        close(fd);
        env->ReleaseStringUTFChars(file_path, path);
        return JNI_FALSE;
    }

    // Verify ELF magic
    if (ehdr.e_ident[EI_MAG0] != ELFMAG0 ||
        ehdr.e_ident[EI_MAG1] != ELFMAG1 ||
        ehdr.e_ident[EI_MAG2] != ELFMAG2 ||
        ehdr.e_ident[EI_MAG3] != ELFMAG3) {
        LOGE("File %s is not an ELF binary (magic mismatch)", path);
        close(fd);
        env->ReleaseStringUTFChars(file_path, path);
        return JNI_FALSE;
    }

    // Verify 64-bit class
    if (ehdr.e_ident[EI_CLASS] != ELFCLASS64) {
        LOGI("Binary %s is 32-bit ELF (EI_CLASS=%d), 16KB alignment applies to 64-bit ARM64/x86_64",
             path, ehdr.e_ident[EI_CLASS]);
        close(fd);
        env->ReleaseStringUTFChars(file_path, path);
        return JNI_TRUE;
    }

    if (ehdr.e_phoff == 0 || ehdr.e_phnum == 0 || ehdr.e_phentsize != sizeof(Elf64_Phdr)) {
        LOGE("Invalid program header table in %s", path);
        close(fd);
        env->ReleaseStringUTFChars(file_path, path);
        return JNI_FALSE;
    }

    if (lseek(fd, static_cast<off_t>(ehdr.e_phoff), SEEK_SET) == -1) {
        LOGE("Failed to seek to program headers in %s", path);
        close(fd);
        env->ReleaseStringUTFChars(file_path, path);
        return JNI_FALSE;
    }

    std::vector<Elf64_Phdr> phdrs(ehdr.e_phnum);
    size_t total_phdr_bytes = ehdr.e_phnum * sizeof(Elf64_Phdr);
    bytes_read = read(fd, phdrs.data(), total_phdr_bytes);
    close(fd);
    env->ReleaseStringUTFChars(file_path, path);

    if (bytes_read != static_cast<ssize_t>(total_phdr_bytes)) {
        LOGE("Failed to read all program headers from %s", path);
        return JNI_FALSE;
    }

    bool has_load_segment = false;
    for (uint16_t i = 0; i < ehdr.e_phnum; ++i) {
        const Elf64_Phdr& ph = phdrs[i];
        if (ph.p_type == PT_LOAD) {
            has_load_segment = true;
            // Alignment must be at least 16 KB (16384 bytes)
            if (ph.p_align < REQUIRED_PAGE_SIZE) {
                LOGI("Segment %u in %s has p_align=%llu < 16384 (4KB aligned)",
                     i, path, static_cast<unsigned long long>(ph.p_align));
                return JNI_FALSE;
            }

            // Virtual address and file offset must align modulo p_align
            if ((ph.p_vaddr % ph.p_align) != (ph.p_offset % ph.p_align)) {
                LOGI("Segment %u in %s vaddr/offset alignment mismatch: vaddr=0x%llx offset=0x%llx align=%llu",
                     i, path,
                     static_cast<unsigned long long>(ph.p_vaddr),
                     static_cast<unsigned long long>(ph.p_offset),
                     static_cast<unsigned long long>(ph.p_align));
                return JNI_FALSE;
            }
        }
    }

    return has_load_segment ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_pocketserver_nativeengine_NativeEngine_nativeCreateRelativeSymlink(
    JNIEnv* env,
    jobject /* thiz */,
    jstring target,
    jstring link_path
) {
    if (target == nullptr || link_path == nullptr) {
        return -EINVAL;
    }

    const char* c_target = env->GetStringUTFChars(target, nullptr);
    const char* c_link = env->GetStringUTFChars(link_path, nullptr);

    int result = 0;
    if (symlink(c_target, c_link) != 0) {
        result = -errno;
        LOGE("Failed to create symlink: %s -> %s, errno=%d (%s)", c_link, c_target, errno, strerror(errno));
    }

    env->ReleaseStringUTFChars(target, c_target);
    env->ReleaseStringUTFChars(link_path, c_link);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_pocketserver_nativeengine_NativeEngine_nativeAtomicRename(
    JNIEnv* env,
    jobject /* thiz */,
    jstring old_path,
    jstring new_path
) {
    if (old_path == nullptr || new_path == nullptr) {
        return -EINVAL;
    }

    const char* c_old = env->GetStringUTFChars(old_path, nullptr);
    const char* c_new = env->GetStringUTFChars(new_path, nullptr);

    int result = 0;
    if (rename(c_old, c_new) != 0) {
        result = -errno;
        LOGE("Failed atomic rename: %s -> %s, errno=%d (%s)", c_old, c_new, errno, strerror(errno));
    }

    env->ReleaseStringUTFChars(old_path, c_old);
    env->ReleaseStringUTFChars(new_path, c_new);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_pocketserver_nativeengine_NativeEngine_nativeFsyncDirectory(
    JNIEnv* env,
    jobject /* thiz */,
    jstring dir_path
) {
    if (dir_path == nullptr) {
        return -EINVAL;
    }

    const char* path = env->GetStringUTFChars(dir_path, nullptr);
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    int result = 0;

    if (fd < 0) {
        result = -errno;
    } else {
        if (fsync(fd) != 0) {
            result = -errno;
        }
        close(fd);
    }

    env->ReleaseStringUTFChars(dir_path, path);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_pocketserver_nativeengine_NativeEngine_nativeGetSystemPageSize(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    return static_cast<jlong>(sysconf(_SC_PAGESIZE));
}

} // extern "C"
