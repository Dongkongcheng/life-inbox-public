/**
 * 为每次列表/搜索刷新分配递增序号，只有最后发出的请求可以更新页面状态。
 */
export const createLatestRequestGuard = () => {
  let latestRequestId = 0

  return {
    begin() {
      latestRequestId += 1
      return latestRequestId
    },
    isLatest(requestId) {
      return requestId === latestRequestId
    },
    invalidate() {
      latestRequestId += 1
    }
  }
}
