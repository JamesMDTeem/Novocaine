import os, re, collections, sys

root = sys.argv[1] if len(sys.argv) > 1 else r"C:/Users/james/Desktop/Haven/Clients/Novocaine/src/haven/automated"
min_repeat = int(sys.argv[2]) if len(sys.argv) > 2 else 3

for dirpath, _, files in os.walk(root):
    for fn in sorted(files):
        if not fn.endswith('.java'):
            continue
        path = os.path.join(dirpath, fn)
        text = open(path, encoding='utf-8', errors='replace').read()
        text = re.sub(r'//.*', '', text)
        text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
        text = re.sub(r'"(\\.|[^"\\])*"', '', text)
        nums = [n for n in re.findall(r'\d+', text) if 2 <= len(n) <= 4]
        cnt = collections.Counter(nums)
        repeated = {n: c for n, c in cnt.items() if c >= min_repeat}
        if repeated:
            print(f"{path.replace(root, '')} : {sorted(repeated.items(), key=lambda x: -x[1])}")
