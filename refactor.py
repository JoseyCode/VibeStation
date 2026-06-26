import os
old_pkg = "com.example.retroclone"
new_pkg = "com.boogie.vibestation"

for root, dirs, files in os.walk("app"):
    for file in files:
        if file.endswith((".java", ".xml", ".kts")):
            path = os.path.join(root, file)
            with open(path, "r") as f:
                content = f.read()
            if old_pkg in content:
                with open(path, "w") as f:
                    f.write(content.replace(old_pkg, new_pkg))
