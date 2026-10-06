cmake_minimum_required(VERSION 4.1.2)
project(guardian_ofd LANGUAGES CXX)

add_library(guardian_ofd SHARED guardian_ofd.cpp)
target_compile_features(guardian_ofd PRIVATE cxx_std_17)
target_compile_options(guardian_ofd PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
