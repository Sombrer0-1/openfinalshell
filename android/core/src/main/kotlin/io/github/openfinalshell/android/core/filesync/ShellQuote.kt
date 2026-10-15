package io.github.openfinalshell.android.core.filesync

/**
 * One POSIX single-quoted argument. `'` becomes `'\''`. A backslash is left as a backslash.
 * NUL cannot be passed through `execve` and is rejected; CR and LF are not this function's problem.
 */
fun shQuote(arg: String): String {
    if (arg.indexOf('\u0000') >= 0) throw IllegalArgumentException("NUL byte in shell argument")
    return "'" + arg.replace("'", "'\\''") + "'"
}
