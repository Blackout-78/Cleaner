package com.blackout.cleaner;

interface IUserService {
    // Transaction id 16777114 is reserved by Shizuku for destroy().
    void destroy() = 16777114;
    String exec(String command) = 1;
}
