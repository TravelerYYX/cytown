export function validateAccount(account: string) {
  return /^[0-9A-Za-z_]{4,20}$/.test(account.trim()) ? '' : '账号需为4-20位字母、数字或下划线';
}
export function validatePassword(password: string) {
  if (password.length < 8 || password.length > 20) return '密码需为8-20位';
  return /\s/.test(password) ? '密码不能包含空格' : '';
}
