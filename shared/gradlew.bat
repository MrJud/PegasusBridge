@rem Gradle wrapper for Windows.
@rem
@rem The mirror image of ./gradlew, and it exists for the mirror-image reason. That
@rem one was added because the repository shipped only a .bat and every documented
@rem `./gradlew` failed on Linux; this directory then had the opposite gap, since the
@rem .bat lived only in the repository root. So `cd shared` followed by `gradlew test`
@rem — the command the porting notes give — could not work here at all.
@rem
@rem Deliberately minimal: it runs the wrapper jar already committed under
@rem gradle\wrapper, which downloads and caches the Gradle distribution named in
@rem gradle-wrapper.properties on first use.
@echo off
setlocal

set "APP_HOME=%~dp0"

set "JAVACMD=java.exe"
if defined JAVA_HOME set "JAVACMD=%JAVA_HOME%\bin\java.exe"

if defined JAVA_HOME if not exist "%JAVACMD%" (
    echo No java at "%JAVACMD%". Point JAVA_HOME at a JDK 21 ^(this builds with 21^). 1>&2
    exit /b 1
)

"%JAVACMD%" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
