<template>
  <div :class="[prefixCls, 'legacy-login-page']">
    <main class="legacy-login-box">
      <div class="legacy-login-logo">冠希管理系统</div>
      <section class="legacy-login-box__body">
        <p class="legacy-login-box__hint">登录即可使用本网站</p>
        <Transition appear enter-active-class="animate__animated animate__fadeInDown">
          <div class="legacy-login-form-wrap">
            <LoginForm />
            <MobileForm />
            <QrCodeForm />
            <RegisterForm />
            <SSOLoginVue />
            <ForgetPasswordForm />
          </div>
        </Transition>
      </section>
    </main>
  </div>
</template>
<script lang="ts" setup>
import { useDesign } from '@/hooks/web/useDesign'

import {
  LoginForm,
  MobileForm,
  QrCodeForm,
  RegisterForm,
  SSOLoginVue,
  ForgetPasswordForm
} from './components'

defineOptions({ name: 'Login' })

const { getPrefixCls } = useDesign()
const prefixCls = getPrefixCls('login')
</script>

<style lang="scss" scoped>
.legacy-login-page {
  position: relative;
  display: flex;
  isolation: isolate;
  min-height: 100%;
  align-items: flex-start;
  justify-content: center;
  padding: 0 12px 24px;
  overflow-x: hidden;
  font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif;
  color: #333;
  background:
    radial-gradient(circle at 18% 20%, rgb(37 152 158 / 28%), transparent 36%),
    radial-gradient(circle at 84% 78%, rgb(52 94 153 / 24%), transparent 42%),
    linear-gradient(135deg, #0b1728 0%, #13283d 52%, #0d3036 100%);
}

.legacy-login-page::before {
  position: absolute;
  inset: 0;
  z-index: -1;
  pointer-events: none;
  content: '';
  background: url('../../assets/lottery/login-grain.svg') repeat;
  opacity: 0.18;
  mix-blend-mode: screen;
}

.legacy-login-box {
  position: relative;
  width: 360px;
  max-width: 100%;
  margin: 7% 0 0;
  box-sizing: border-box;
}

.legacy-login-logo {
  margin-bottom: 30px;
  font-size: 35px;
  font-weight: 300;
  line-height: 1.2;
  color: #f2fafb;
  text-align: center;
  text-shadow: 0 3px 18px rgb(0 0 0 / 30%);
}

.legacy-login-box__body {
  width: 100%;
  padding: 20px;
  border-top: 3px solid #43bec1;
  color: #666;
  box-sizing: border-box;
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 20px 48px rgb(0 10 24 / 28%);
}

.legacy-login-box__hint {
  margin: 0 0 20px;
  color: #555;
  font-size: 14px;
  line-height: 20px;
  text-align: center;
}

.legacy-login-form-wrap {
  width: 100%;
}

@media (width <= 767px) {
  .legacy-login-page {
    padding-top: 0;
  }

  .legacy-login-box {
    width: 90%;
    max-width: 360px;
    margin-top: 20px;
  }

  .legacy-login-logo {
    margin-bottom: 20px;
    font-size: 30px;
  }
}
</style>

<style lang="scss">
.legacy-login-page .login-form {
  width: 100%;
  min-width: 0;

  .el-row {
    margin: 0 !important;
    max-width: 100%;
  }

  .el-col {
    padding: 0 !important;
    min-width: 0;
    max-width: 100%;
  }

  .el-form-item {
    margin-bottom: 15px;
  }

  .legacy-login-form-title,
  .legacy-login-remember {
    display: none;
  }

  .el-input__wrapper {
    width: 100%;
    box-sizing: border-box;
    min-height: 34px;
    border-radius: 0;
    box-shadow: 0 0 0 1px #d2d6de inset;
  }

  .el-input__wrapper.is-focus {
    box-shadow: 0 0 0 1px #3c8dbc inset;
  }

  .el-button--primary {
    width: 87px;
    min-height: 34px;
    border-color: #367fa9;
    border-radius: 0;
    background: #3c8dbc;
  }

  .el-button--primary:hover {
    border-color: #367fa9;
    background: #367fa9;
  }

  .el-checkbox__input.is-checked .el-checkbox__inner {
    border-color: #3c8dbc;
    background-color: #3c8dbc;
  }

  .legacy-login-action .el-form-item__content {
    justify-content: flex-end;
  }
}
</style>
