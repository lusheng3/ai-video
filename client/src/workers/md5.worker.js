import SparkMD5 from 'spark-md5'

const READ_SIZE = 2 * 1024 * 1024

self.onmessage = (event) => {
  const { file } = event.data
  if (!file) {
    self.postMessage({ type: 'error', message: '未收到文件' })
    return
  }
  const spark = new SparkMD5.ArrayBuffer()
  const reader = new FileReader()
  let offset = 0

  const readNext = () => {
    const end = Math.min(offset + READ_SIZE, file.size)
    reader.readAsArrayBuffer(file.slice(offset, end))
  }

  reader.onload = (e) => {
    spark.append(e.target.result)
    offset += READ_SIZE
    const progress = Math.min(100, Math.round((offset / file.size) * 100))
    self.postMessage({ type: 'progress', progress })
    if (offset < file.size) {
      readNext()
    } else {
      self.postMessage({ type: 'done', md5: spark.end() })
    }
  }

  reader.onerror = () => {
    self.postMessage({ type: 'error', message: '读取文件失败' })
  }

  readNext()
}
