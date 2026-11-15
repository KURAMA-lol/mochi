# Unpacks downloaded model archives (tar.bz2) for Nana's offline speech. Plain stdlib only.
import os
import shutil
import tarfile


def unpack(tar_path, dest):
    try:
        tmp = dest + ".part"
        shutil.rmtree(tmp, ignore_errors=True)
        os.makedirs(tmp)
        with tarfile.open(tar_path, "r:bz2") as t:
            try:
                t.extractall(tmp, filter="data")
            except TypeError:
                t.extractall(tmp)
        names = os.listdir(tmp)
        src = os.path.join(tmp, names[0]) if len(names) == 1 and os.path.isdir(os.path.join(tmp, names[0])) else tmp
        shutil.rmtree(dest, ignore_errors=True)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        os.rename(src, dest)
        shutil.rmtree(tmp, ignore_errors=True)
        return "ok"
    except Exception as e:
        return "Unpack failed: %s: %s" % (type(e).__name__, e)
