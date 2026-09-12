import SparkMD5 from 'spark-md5';

const HASH_CHUNK_SIZE = 2 * 1024 * 1024;

/** 按文件字节顺序计算完整 MD5，固定分块读取以限制内存占用。 */
export async function calculateFileMd5(file: Blob): Promise<string> {
  const spark = new SparkMD5.ArrayBuffer();
  for (let offset = 0; offset < file.size; offset += HASH_CHUNK_SIZE) {
    const chunk = await file.slice(offset, offset + HASH_CHUNK_SIZE).arrayBuffer();
    spark.append(chunk);
  }
  return spark.end();
}
