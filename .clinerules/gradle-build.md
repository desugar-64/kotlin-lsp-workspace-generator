CRITICAL: When working with Kotlin Gradle projects using build.kts, you MUST use appropriate Gradle commands and follow proper build procedures. Always use ./gradlew (Gradle Wrapper) for consistency.

CORE BUILD COMMANDS:

1. Clean Build Process
   - ALWAYS start complex builds with: `./gradlew clean`
   - For full clean rebuild: `./gradlew clean build`
   - Clean removes all build artifacts and ensures fresh compilation

2. Primary Build Commands
   - `./gradlew build` - Complete build with tests
   - `./gradlew assemble` - Build without running tests
   - `./gradlew assembleDebug` - Build debug variant only
   - `./gradlew compileKotlin` - Compile Kotlin sources only
   - `./gradlew compileTestKotlin` - Compile test sources only

3. Dependency Management
   - `./gradlew dependencies` - Show all dependencies
   - `./gradlew dependencies --configuration implementation` - Show specific configuration
   - `./gradlew dependencyInsight --dependency [name]` - Analyze specific dependency
   - `./gradlew buildEnvironment` - Show build script dependencies

BUILD EXECUTION STRATEGY:

SUCCESS SCENARIO:
- When build succeeds → Use quiet mode: `./gradlew -q [task]`
- Quiet mode saves tokens by suppressing verbose output
- Example: `./gradlew -q build` or `./gradlew -q assembleDebug`

ERROR SCENARIO:
- When build fails → Use full output with stacktrace:
  - `./gradlew [task] --stacktrace` - Show stack traces for failures
  - `./gradlew [task] --info` - Show info-level logging
  - `./gradlew [task] --debug` - Show debug-level logging (most verbose)
  - `./gradlew [task] --scan` - Generate build scan for detailed analysis

ERROR ANALYSIS COMMANDS:
- `./gradlew build --stacktrace` - Full build with error details
- `./gradlew compileKotlin --stacktrace` - Compilation errors with traces
- `./gradlew check --continue --stacktrace` - Run all checks, continue on failure

WORKFLOW INTEGRATION:
1. For successful builds → Always use `-q` flag to minimize output
2. For failed builds → Always use `--stacktrace` to get detailed error information
3. For dependency issues → Use `dependencies` or `dependencyInsight` commands
4. For complex debugging → Use `--info` or `--debug` flags

COMMAND SELECTION LOGIC:
- Quick compilation check: `./gradlew -q compileKotlin`
- Debug build: `./gradlew -q assembleDebug` 
- Full verification: `./gradlew -q build`
- Error investigation: `./gradlew build --stacktrace`
- Dependency conflicts: `./gradlew dependencies --stacktrace`

MANDATORY: Always specify the appropriate verbosity level based on expected outcome to optimize token usage while ensuring proper error diagnosis when needed.