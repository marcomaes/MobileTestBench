❯ How can I build the distribution?

⏺ Run:

./gradlew buildPlugin

Output: build/distributions/MobileTestBench-0.1.0.zip. It includes LICENSE and NOTICE.

Install it in the IDE: Settings | Plugins | gear icon | Install Plugin from Disk. Do not unzip it.

I already built it earlier. That zip includes the Display tab fix, 11,682,319 bytes. The buildPlugin --rerun-tasks run just now rebuilt it too.

Other options:
- ./gradlew test buildPlugin runs the tests first.
- ./gradlew verifyPlugin runs Plugin Verifier, about 6 min.
- ./gradlew signPlugin publishPlugin needs env vars. Run it only with the owner.                                                                                                                                                                  
  