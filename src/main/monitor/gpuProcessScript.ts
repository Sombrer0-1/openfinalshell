import { SENTINEL } from './script'

const QUERY_GPU =
  '--query-gpu=index,uuid,name,utilization.gpu,memory.used,memory.total,temperature.gpu --format=csv,noheader,nounits'
const QUERY_APPS =
  '--query-compute-apps=gpu_uuid,pid,process_name,used_gpu_memory --format=csv,noheader,nounits'
const QUERY_PMON = 'pmon -c 2 -d 1 -s u'

/** 有 timeout 就限时，没有就直接跑。输出留在变量里，后面还要用 PID 查用户。 */
function capture(name: string, seconds: number, args: string): string {
  return (
    `${name}=$(if command -v nvidia-smi >/dev/null 2>&1; then ` +
    `if command -v timeout >/dev/null 2>&1; then timeout ${seconds} nvidia-smi ${args} 2>/dev/null; ` +
    `else nvidia-smi ${args} 2>/dev/null; fi; fi)`
  )
}

function emit(name: string): string {
  return `printf '%s\\n' "$${name}"`
}

/**
 * 打开 GPU 进程页后写入持久 sh 通道的一帧。段名只能是大写字母。
 * nvidia-smi 不提供用户，所以用同一批 PID 再问一次 ps；没有 ps -o 时读 /proc。
 */
export function buildGpuProcessFrame(seq: number): string {
  return [
    `echo "${SENTINEL.begin(seq)}"`,
    `echo "${SENTINEL.section('HASGPU')}"`,
    'command -v nvidia-smi >/dev/null 2>&1 && echo nvidia || echo no',
    capture('ofs_gpu_dev', 2, QUERY_GPU),
    `echo "${SENTINEL.section('GPUDEV')}"`,
    emit('ofs_gpu_dev'),
    capture('ofs_gpu_apps', 2, QUERY_APPS),
    `echo "${SENTINEL.section('GPUAPPS')}"`,
    emit('ofs_gpu_apps'),
    capture('ofs_gpu_mon', 4, QUERY_PMON),
    `echo "${SENTINEL.section('GPUMON')}"`,
    emit('ofs_gpu_mon'),
    `echo "${SENTINEL.section('GPUUSER')}"`,
    'ofs_gpu_pids=$(printf \'%s\\n\' "$ofs_gpu_apps" | awk -F, \'NF>=2 { gsub(/ /,"",$2); if ($2 ~ /^[0-9]+$/) print $2 }\'; printf \'%s\\n\' "$ofs_gpu_mon" | awk \'NF>=2 && $1 !~ /^#/ && $2 ~ /^[0-9]+$/ { print $2 }\')',
    'ofs_gpu_pids=$(printf \'%s\\n\' $ofs_gpu_pids | awk \'NF && !seen[$1]++ { printf "%s%s", sep, $1; sep="," }\')',
    'if [ -n "$ofs_gpu_pids" ] && ps -o pid=,user= -p 1 >/dev/null 2>&1; then ps -o pid=,user= -p "$ofs_gpu_pids" 2>/dev/null; elif [ -n "$ofs_gpu_pids" ]; then',
    'IFS=,',
    'for ofs_pid in $ofs_gpu_pids; do',
    'ofs_uid=$(awk \'/^Uid:/ { print $2; exit }\' "/proc/$ofs_pid/status" 2>/dev/null)',
    'ofs_name=$(id -nu "$ofs_uid" 2>/dev/null || awk -F: -v u="$ofs_uid" \'u != "" && $3 == u { print $1; exit }\' /etc/passwd)',
    '[ -n "$ofs_name" ] && echo "$ofs_pid $ofs_name"',
    'done',
    'unset IFS',
    'fi',
    `echo "${SENTINEL.end(seq)}"`
  ].join('\n') + '\n'
}
