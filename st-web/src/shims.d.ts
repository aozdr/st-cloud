declare module 'spark-md5' {
  interface SparkMD5ArrayBuffer {
    append(data: ArrayBuffer): void;
    end(): string;
  }
  const SparkMD5: {
    ArrayBuffer: new () => SparkMD5ArrayBuffer;
  };
  export default SparkMD5;
}