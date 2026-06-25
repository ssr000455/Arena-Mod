#!/data/data/com.termux/files/usr/bin/bash

OUT_DIR="/storage/emulated/0/Download"
mkdir -p "$OUT_DIR" 2>/dev/null
DATE=$(date +'%Y-%m-%d')
RAND_SUFFIX=$(printf "%04d" $((RANDOM % 10000)))
OUT_FILE="${OUT_DIR}/${DATE}-${RAND_SUFFIX}.txt"

err=$(mktemp)
p=$(pwd)

exec > "$OUT_FILE"

cat <<DOC
请基于以下项目完整结构与文件内容分析，工作环境为Android Termux终端。
————————————————————
项目路径：$p
生成时间：$(date +'%Y-%m-%d %H:%M:%S')
————————————————————
DOC

cat <<DOC

项目源代码文件内容
DOC

c=0
while IFS= read -r -d '' fpath; do
    if [ -f "$fpath" ] && [ -s "$fpath" ]; then
        c=$((c+1))
        echo ""
        echo "文件路径：$fpath"
        cat "$fpath"
        echo ""
    fi
done < <(find . -type f \( \
    -name "*.py" -o -name "*.java" -o -name "*.kt" -o -name "*.gradle" -o -name "*.gradle.kts" \
    -o -name "*.xml" -o -name "*.json" -o -name "*.properties" -o -name "*.txt" -o -name "*.md" \
    -o -name "*.mcmeta" -o -name "*.yml" -o -name "*.yaml" -o -name "*.cfg" \
    \) \
    ! -path "*/build/*" \
    ! -path "*/.git/*" \
    ! -path "*/node_modules/*" \
    ! -path "*/__pycache__/*" \
    -print0)

echo "有效源代码文件数：$c"
echo "生成时间：$(date +'%Y-%m-%d %H:%M:%S')"
echo "错误信息："
if [ -s "$err" ]; then
    cat "$err"
else
    echo "无"
fi

rm -f "$err"
cat <<DOC
项目结构树
DOC

tree . >> "$OUT_FILE"

exec > /dev/tty
echo "生成完成"
echo "输出文件：$OUT_FILE"
echo "有效源代码文件数：$c"
