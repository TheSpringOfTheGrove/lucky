<template>
  <div class="lucky-page lucky-legacy-content lucky-fish-info-page">
    <section class="fish-info-box">
      <div class="fish-info-box__header">飞鱼IM信息</div>
    </section>
    <div class="fish-info-content">
      <p><strong>飞鱼下载地址:</strong> <span class="fish-info-muted-link">飞鱼聊天系统已下架</span></p>
      <p>
        <strong>蓝鲸下载地址:</strong>
        <a href="https://notebook.lanjing666.xyz/" rel="noopener noreferrer" target="_blank"
          >https://notebook.lanjing666.xyz/</a
        >
      </p>
      <p><strong>说明:</strong> 蓝鲸可以不用下载app，请在下载地址中看详细信息</p>
      <p>
        <strong>开码地址:</strong>
        https://m01.cc138006.com/#/history/ygxy5?VNK=a6596cda
      </p>
    </div>
  </div>
</template>

<style scoped lang="less">
.lucky-fish-info-page {
  min-height: 100%;
  padding: 0;
}

.fish-info-box {
  margin: 0;
  border-top: 3px solid #00c0ef;
  background: #fff;
}

.fish-info-box__header {
  min-height: 41px;
  padding: 10px;
  border-bottom: 1px solid #f4f4f4;
  color: #444;
  font-size: 15px;
  font-weight: 400;
  line-height: 20px;
  box-sizing: border-box;
}

.fish-info-content {
  width: 80%;
  margin-top: 30px;
  margin-left: 30px;
  color: #333;
  font-size: 14px;
  line-height: 20px;
  word-break: break-all;
}

.fish-info-content p {
  margin: 0 0 10px;
}

.fish-info-content strong {
  font-weight: 700;
}

.fish-info-content a,
.fish-info-muted-link {
  color: #3c8dbc;
  text-decoration: none;
}

.fish-info-content a:hover {
  color: #367fa9;
  text-decoration: underline;
}

@media (max-width: 767px) {
  .fish-info-content {
    width: calc(100% - 30px);
    margin-top: 20px;
    margin-left: 15px;
  }
}
</style>
