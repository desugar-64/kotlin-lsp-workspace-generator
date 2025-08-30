Whenever you are modifying code that needs to build successfully, you must follow this loop:

1. Modify Code
- Apply the requested or required code change.
- Ensure the modification integrates cleanly with the existing codebase.

2. Build Project
- Trigger a build/compile of the project immediately after the modification.

3. Analyze Build Result
- If the build succeeds -> stop, return the successful result to the user.
- If the build fails -> proceed to step 4.

4. Fix Errors
- Carefully analyze the error message(s).
- Determine the most probable root cause.
- Modify the code to fix the problem.

5. Retry Build
- Rebuild the project with the applied fix.
- Return to step 3.

6. Retry Policy
- Repeat steps 3 to 5 until either:
  - Build succeeds -> stop and return success.
  - Maximum of 20 retries reached -> stop, report failure, and ask the user to intervene.