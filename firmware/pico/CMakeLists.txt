cmake_minimum_required(VERSION 3.13)

include($ENV{PICO_SDK_PATH}/external/pico_sdk_import.cmake)

project(p16mix C CXX ASM)
pico_sdk_init()

add_executable(p16mix
    src/main.c
    ../common/mix.c
    ../common/protocol.c
    ../common/ultranet.c
)
target_include_directories(p16mix PRIVATE ../common)
target_link_libraries(p16mix pico_stdlib)
pico_enable_stdio_usb(p16mix 1)
pico_enable_stdio_uart(p16mix 0)
pico_add_extra_outputs(p16mix)
