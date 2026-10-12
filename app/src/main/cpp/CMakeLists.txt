cmake_minimum_required(VERSION 3.22.1)
project(launcher_native C)

add_library(launcher SHARED pty.c)
target_compile_options(launcher PRIVATE -Wall -Wextra -Werror)
target_link_libraries(launcher log)
