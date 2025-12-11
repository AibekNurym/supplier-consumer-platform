const shouldProfile = () => {
  const flag = process.env.ENABLE_PROFILING;
  if (!flag) return false;
  return flag === "true" || flag === "1";
};

export const requestProfiler = () => {
  if (!shouldProfile()) {
    return (_req, _res, next) => next();
  }

  return (req, res, next) => {
    const start = process.hrtime.bigint();

    res.on("finish", () => {
      const end = process.hrtime.bigint();
      const durationMs = Number(end - start) / 1e6;
      const method = req.method;
      const url = req.originalUrl || req.url;
      const status = res.statusCode;

      console.log(
        `[perf] ${method} ${url} - ${status} - ${durationMs.toFixed(2)}ms`
      );
    });

    next();
  };
};

