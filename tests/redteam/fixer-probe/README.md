# fixer-probe

Copy of ../leaks-probe (command /lkprobe) without `BukkitScheduler.getPendingTasks()`, which modules may no
longer call (crash-taskkill fix). Used to re-run the leaks-raw / leaks-threads checks after the fixes.
